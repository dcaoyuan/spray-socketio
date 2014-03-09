package spray.contrib.socketio.cluster

import akka.actor._
import akka.util.{Timeout, ByteString}
import akka.pattern.ask
import org.parboiled2.ParseError
import scala.collection.immutable
import scala.concurrent.{Promise, ExecutionContext, Future}
import scala.concurrent.duration._
import spray.can.websocket.frame.TextFrame
import spray.contrib.socketio
import spray.contrib.socketio.ConnectionContext
import spray.contrib.socketio.packet.ConnectPacket
import spray.contrib.socketio.packet.DisconnectPacket
import spray.contrib.socketio.packet.EventPacket
import spray.contrib.socketio.packet.HeartbeatPacket
import spray.contrib.socketio.packet.JsonPacket
import spray.contrib.socketio.packet.MessagePacket
import spray.contrib.socketio.packet.Packet
import spray.contrib.socketio.packet.PacketParser
import spray.contrib.socketio.transport.Transport
import spray.http.HttpOrigin
import spray.http.Uri
import spray.json.JsValue
import akka.contrib.pattern.{DistributedPubSubMediator, DistributedPubSubExtension, ShardRegion, ClusterSharding}
import akka.persistence.EventsourcedProcessor
import spray.contrib.socketio.packet.JsonPacket
import scala.Some
import org.parboiled2.ParseError
import spray.contrib.socketio.packet.DisconnectPacket
import spray.contrib.socketio.packet.ConnectPacket
import spray.contrib.socketio.packet.MessagePacket
import akka.persistence.journal.leveldb.SharedLeveldbJournal

object ConnectionActive {
  case object AskConnectedTime

  case object Pause
  case object Awake

  sealed trait Command {
    def sessionId: String
  }
  final case class Connecting(sessionId: String, query: Uri.Query, origins: Seq[HttpOrigin], transportConnection: ActorRef, transport: Transport) extends Command

  final case class SendMessage(sessionId: String, msg: String, endpoint: String) extends Command
  final case class SendJson(sessionId: String, json: String, endpoint: String) extends Command
  final case class SendEvent(sessionId: String, name: String, args: List[JsValue], endpoint: String) extends Command
  final case class SendPackets(sessionId: String, packets: Seq[Packet]) extends Command

  final case class OnPayload(sessionId: String, transportConnection: ActorRef, payload: ByteString) extends Command
  final case class OnGet(sessionId: String, transportConnection: ActorRef) extends Command
  final case class OnPost(sessionId: String, transportConnection: ActorRef, payload: ByteString) extends Command
  final case class OnFrame(sessionId: String, transportConnection: ActorRef, frame: TextFrame) extends Command

  sealed trait Event
  final case class Connected(sessionId: String, query: Uri.Query, origins: Seq[HttpOrigin]) extends Event

  val actorResolveTimeout = socketio.config.getInt("server.actor-selection-resolve-timeout")

  val idExtractor: ShardRegion.IdExtractor = {
    case cmd: Command => (cmd.sessionId, cmd)
  }

  val shardResolver: ShardRegion.ShardResolver = msg => msg match {
    case cmd: Command => (math.abs(cmd.sessionId.hashCode) % 100).toString
  }

  val shardName: String = "ConnectionActive"

  def selectOrCreateConnectionActive(system: ActorSystem, sessionId: String)(implicit ec: ExecutionContext): Future[ActorRef] = {
    val connectionActiveRegion = selectConnectionActive(system, sessionId)
    val p = Promise[ActorRef]()

    implicit val timeout = Timeout(actorResolveTimeout)
    val f = connectionActiveRegion ? Identify(None)
    f.onSuccess {
      case ActorIdentity(_, Some(ref)) => p.success(connectionActiveRegion)
      case _ => p.failure(new RuntimeException())
    }
    f.onFailure {
      case e =>
        p.failure(e)
    }
    p.future
  }

  def selectConnectionActive(system: ActorSystem, sessionId: String): ActorRef = {
    val connectionActiveRegion = ClusterSharding(system).shardRegion(ConnectionActive.shardName)
    connectionActiveRegion
  }

}

/**
 *
 * transportConnection <1..n--1> connectionActive <1--1> connContext <1--n> transport
 */
class ConnectionActive extends EventsourcedProcessor with ActorLogging {
  import ConnectionActive._
  import context.dispatcher

  import DistributedPubSubMediator.Publish
  // activate the extension
  val mediator = DistributedPubSubExtension(context.system).mediator

  var connectionContext: Option[ConnectionContext] = None
  var transportConnection: ActorRef = _

  var closeTimeout: Option[Cancellable] = Some(context.system.scheduler.scheduleOnce(socketio.Settings.CloseTimeout.seconds) {
    doCloseTimeout()
  })

  // It seems socket.io client may fire heartbeat only when it received heartbeat
  // from server, or, just bounce heartheat instead of firing heartbeat standalone.
  var heartbeatHandler: Option[Cancellable] = None
  var heartbeatTimeout: Option[Cancellable] = None

  var pendingPackets = immutable.Queue[Packet]()

  val startTime = System.currentTimeMillis

  def heartbeatInterval = socketio.Settings.HeartbeatTimeout * 0.618

  def connected() {
    sendPacket(ConnectPacket())

    if (heartbeatHandler.isEmpty) {
      heartbeatHandler = Some(context.system.scheduler.schedule(0.seconds, heartbeatInterval.seconds) {
        sendPacket(HeartbeatPacket)
      })
    }

    heartbeatTimeout foreach (_.cancel)
    heartbeatTimeout = Some(context.system.scheduler.scheduleOnce(socketio.Settings.HeartbeatTimeout.seconds) {
      doHeartbeatTimeout()
    })
  }

  def update(event: Event) = {
    event match {
      case Connected(sessionId, query, origins) => connectionContext = Some(new ConnectionContext(sessionId, query, origins, self))
    }
  }

  override def receiveRecover: Receive = {
    case event: Event => update(event)
  }

  override def receiveCommand: Receive = processing


  def processing: Receive = {
    case conn @ Connecting(sessionId, query, origins, transportConn, transport) =>
      closeTimeout foreach (_.cancel)
      closeTimeout = None

      log.debug("Connecting request: {}", sessionId)
      transportConnection = transportConn
      connectionContext match {
        case Some(existed) =>
          existed.bindTransport(transport)
          connected()
        case None =>
          persist(Connected(sessionId, query, origins)) {
            event =>
              update(event)
              connectionContext.foreach(_.bindTransport(transport))
              connected()
          }
      }

      heartbeatTimeout foreach (_.cancel)
      heartbeatTimeout = Some(context.system.scheduler.scheduleOnce(socketio.Settings.HeartbeatTimeout.seconds) {
        doHeartbeatTimeout()
      })

    case Pause                                =>

    case OnFrame(sessionId, transportConnection, frame)  => onFrame(transportConnection, frame)
    case OnGet(sessionId, transportConnection)           => onGet(transportConnection)
    case OnPost(sessionId, transportConnection, payload) => onPost(transportConnection, payload)

    case SendMessage(sessionId, msg, endpoint)           => sendMessage(msg, endpoint)
    case SendJson(sessionId, json, endpoint)             => sendJson(json, endpoint)
    case SendEvent(sessionId, name, args, endpoint)      => sendEvent(name, args, endpoint)
    case SendPackets(sessionId, packets)                 => enqueueAndMaySendPacket(packets: _*)

    case AskConnectedTime =>
      sender() ! System.currentTimeMillis - startTime
  }

  def doHeartbeatTimeout() {
    heartbeatHandler foreach (_.cancel)
    heartbeatHandler = None
    log.debug("{}: heartbeat timeout, will close in {} seconds", self.path, socketio.Settings.CloseTimeout)
    closeTimeout = Some(context.system.scheduler.scheduleOnce(socketio.Settings.CloseTimeout.seconds) {
      doCloseTimeout()
    })
  }

  def doCloseTimeout() {
    log.warning("{}: stoped due to close timeout", self.path)
    context.stop(self)
  }

  def pauseHeartbeat {
    log.debug("{}: heartbeat paused.", self.path)
    heartbeatHandler foreach (_.cancel)
    heartbeatHandler = None
  }

  def resumeHeartbeat {
    log.debug("{}: heartbeat resumed.", self.path)
    heartbeatHandler = Some(context.system.scheduler.schedule(0.seconds, heartbeatInterval.seconds) {
      sendPacket(HeartbeatPacket)
    })
  }

  private def onPayload(transportConnection: ActorRef, payload: ByteString) {
    try {
      PacketParser(payload) foreach onPacket
    } catch {
      case ex: ParseError => log.warning("Invalid socket.io packet: {} ...", payload.take(50).utf8String)
      case ex: Throwable  => log.warning("Exception during parse socket.io packet {}", ex.getMessage)
    }
  }

  private def onPacket(packet: Packet) {
    packet match {
      case HeartbeatPacket =>
        closeTimeout foreach (_.cancel)
        closeTimeout = None
        heartbeatTimeout foreach (_.cancel)
        heartbeatTimeout = Some(context.system.scheduler.scheduleOnce(socketio.Settings.HeartbeatTimeout.seconds) {
          doHeartbeatTimeout()
        })

      case ConnectPacket(endpoint, args) =>
        connectionContext foreach { ctx =>
          // bounce connect packet back to client
          sendPacket(packet)
          mediator ! Publish(Namespace.namespaceFor(packet.endpoint), Namespace.OnPacket(packet, ctx))
          //Namespace.tryDispatch(context.system, packet.endpoint, Namespace.OnPacket(packet, ctx))
        }

      case DisconnectPacket(endpoint) =>
        connectionContext foreach { ctx =>
          mediator ! Publish(Namespace.namespaceFor(packet.endpoint), Namespace.OnPacket(packet, ctx))
          //Namespace.tryDispatch(context.system, packet.endpoint, Namespace.OnPacket(packet, ctx))
        }
        if (endpoint == "") {
          context.stop(self)
        }

      case _ =>
        connectionContext foreach { ctx =>
          mediator ! Publish(Namespace.namespaceFor(packet.endpoint), Namespace.OnPacket(packet, ctx))
          //Namespace.dispatch(context.system, packet.endpoint, Namespace.OnPacket(packet, ctx))
        }
    }
  }

  def onFrame(transportConnection: ActorRef, frame: TextFrame) {
    onPayload(transportConnection, frame.payload)
  }

  def onGet(transportConnection: ActorRef) {
    connectionContext foreach { ctx =>
      pendingPackets = ctx.transport.writeSingle(ctx, transportConnection, isSendingNoopWhenEmpty = true, pendingPackets)
    }
  }

  def onPost(transportConnection: ActorRef, payload: ByteString) {
    connectionContext foreach { ctx =>
      // response an empty entity to release POST before message processing
      ctx.transport.write(ctx, transportConnection, "")
    }
    onPayload(transportConnection, payload)
  }

  private def properEndpoint(endpoint: String) = if (endpoint == Namespace.DEFAULT_NAMESPACE) "" else endpoint

  def sendMessage(msg: String, endpoint: String) {
    val packet = MessagePacket(-1L, false, properEndpoint(endpoint), msg)
    sendPacket(packet)
  }

  def sendJson(json: String, endpoint: String) {
    val packet = JsonPacket(-1L, false, properEndpoint(endpoint), json)
    sendPacket(packet)
  }

  def sendEvent(name: String, args: List[JsValue], endpoint: String) {
    val packet = EventPacket(-1L, false, properEndpoint(endpoint), name, args)
    sendPacket(packet)
  }

  def sendPacket(packets: Packet*) {
    enqueueAndMaySendPacket(packets: _*)
  }

  private def enqueueAndMaySendPacket(packets: Packet*) {
    packets foreach { packet => pendingPackets = pendingPackets.enqueue(packet) }
    log.debug("Enqueued {}, pendingPackets: {}", packets, pendingPackets)
    connectionContext foreach { ctx =>
      pendingPackets = ctx.transport.flushOrWait(ctx, transportConnection, pendingPackets: immutable.Queue[Packet])
    }
  }
}

