package spray.contrib.socketio

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Cancellable
import akka.actor.Props
import akka.util.ByteString
import org.parboiled2.ParseError
import scala.collection.immutable
import scala.concurrent.Future
import scala.concurrent.duration._
import spray.can.websocket.frame.TextFrame
import spray.contrib.socketio
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

object ConnectionActive {
  case object AskConnectedTime

  case object Pause
  case object Awake
  final case class Connecting(sessionId: String, query: Uri.Query, origins: Seq[HttpOrigin], transportConnection: ActorRef, transport: Transport)

  final case class SendMessage(msg: String, endpoint: String)
  final case class SendJson(json: JsValue, endpoint: String)
  final case class SendEvent(name: String, args: List[JsValue], endpoint: String)
  final case class SendPackets(packets: Seq[Packet])

  final case class OnFrame(transportConnection: ActorRef, frame: TextFrame)
  final case class OnGet(transportConnection: ActorRef)
  final case class OnPost(transportConnection: ActorRef, payload: ByteString)

  val actorResolveTimeout = socketio.config.getInt("server.actor-selection-resolve-timeout")

  def actorPath(sessionId: String) = "/user/" + sessionId

  def selectOrCreateConnectionActive(system: ActorSystem, sessionId: String): Future[ActorRef] = {
    import system.dispatcher
    system.actorSelection(actorPath(sessionId)).resolveOne(actorResolveTimeout.seconds).recover {
      case _: Throwable => system.actorOf(Props(classOf[ConnectionActive]), name = sessionId)
    }
  }

  def selectConnectionActive(system: ActorSystem, sessionId: String): Future[ActorRef] = {
    import system.dispatcher
    system.actorSelection(actorPath(sessionId)).resolveOne(actorResolveTimeout.seconds)
  }
}

/**
 *
 * transportConnection <1..n--1> connectionActive <1--1> connContext <1--n> transport
 */
class ConnectionActive extends Actor with ActorLogging {
  import ConnectionActive._
  import context.dispatcher

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

  def receive = processing

  def processing: Receive = {
    case Connecting(sessionId, query, origins, transportConn, transport) =>
      closeTimeout foreach (_.cancel)
      closeTimeout = None

      log.debug("Connecting request: {}", sessionId)
      transportConnection = transportConn
      connectionContext match {
        case Some(existed) =>
          existed.bindTransport(transport)
        case None =>
          val newContext = new ConnectionContext(sessionId, query, origins, self)
          newContext.bindTransport(transport)
          connectionContext = Some(newContext)
      }

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

    case Pause                                =>

    case OnFrame(transportConnection, frame)  => onFrame(transportConnection, frame)
    case OnGet(transportConnection)           => onGet(transportConnection)
    case OnPost(transportConnection, payload) => onPost(transportConnection, payload)

    case SendMessage(msg, endpoint)           => sendMessage(msg, endpoint)
    case SendJson(json, endpoint)             => sendJson(json, endpoint)
    case SendEvent(name, args, endpoint)      => sendEvent(name, args, endpoint)
    case SendPackets(packets)                 => enqueueAndMaySendPacket(packets: _*)

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
      case ex: ParseError => log.warning("Invalid socket.io packet: {}", ex.formatTraces)
      case _: Throwable   =>
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
          Namespace.tryDispatch(context.system, packet.endpoint, Namespace.OnPacket(packet, ctx))
        }

      case DisconnectPacket(endpoint) =>
        connectionContext foreach { ctx =>
          Namespace.tryDispatch(context.system, packet.endpoint, Namespace.OnPacket(packet, ctx))
        }
        if (endpoint == "") {
          context.stop(self)
        }

      case _ =>
        connectionContext foreach { ctx =>
          Namespace.dispatch(context.system, packet.endpoint, Namespace.OnPacket(packet, ctx))
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

  def sendJson(json: JsValue, endpoint: String) {
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

