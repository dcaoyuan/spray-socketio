package spray.contrib.socketio

import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Cancellable
import akka.contrib.pattern.DistributedPubSubMediator
import akka.contrib.pattern.DistributedPubSubMediator.Publish
import akka.contrib.pattern.DistributedPubSubMediator.SubscribeAck
import akka.pattern.ask
import akka.event.LoggingAdapter
import akka.util.ByteString
import org.parboiled2.ParseError
import scala.collection.immutable
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Failure
import scala.util.Success
import spray.can.websocket.frame.TextFrame
import spray.contrib.socketio
import spray.contrib.socketio.packet.AckPacket
import spray.contrib.socketio.packet.ConnectPacket
import spray.contrib.socketio.packet.DataPacket
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

object ConnectionActive {

  val shardName: String = "connectionActives"

  case object AskConnectedTime

  sealed trait Event extends Serializable
  final case class Connected(sessionId: String, query: Uri.Query, origins: Seq[HttpOrigin], transportConnection: ActorRef, transport: Transport) extends Event
  final case class UpdatePackets(packets: Seq[Packet]) extends Event

  sealed trait Command {
    def sessionId: String
  }

  final case class CreateSession(sessionId: String) extends Command
  final case class Connecting(sessionId: String, query: Uri.Query, origins: Seq[HttpOrigin], transportConnection: ActorRef, transport: Transport) extends Command

  // called by connection
  final case class OnGet(sessionId: String, transportConnection: ActorRef) extends Command
  final case class OnPost(sessionId: String, transportConnection: ActorRef, payload: ByteString) extends Command
  final case class OnFrame(sessionId: String, transportConnection: ActorRef, frame: TextFrame) extends Command

  // called by business logic
  final case class SendMessage(sessionId: String, endpoint: String, msg: String) extends Command
  final case class SendJson(sessionId: String, endpoint: String, json: String) extends Command
  final case class SendEvent(sessionId: String, endpoint: String, name: String, args: Either[String, Seq[String]]) extends Command
  final case class SendPackets(sessionId: String, packets: Seq[Packet]) extends Command

  final case class SendAck(sessionId: String, originalPacket: DataPacket, args: String) extends Command

  final case class Subscribe(sessionId: String, endpoint: String, room: String) extends Command
  final case class Unsubscribe(sessionId: String, endpoint: String, room: String) extends Command

  /**
   * ask me to publish an OnBroadcast data
   */
  final case class Broadcast(sessionId: String, room: String, packet: Packet) extends Command

  /**
   * Broadcast event to be published or recevived
   */
  final case class OnBroadcast(sessionId: String, room: String, packet: Packet)

  /**
   * Packet event to be published
   */
  final case class OnPacket[T <: Packet](packet: T, connContext: ConnectionContext)
}

/**
 *
 * transportConnection <1..n--1> connectionActive <1--1> connContext <1--n> transport
 */
trait ConnectionActive { _: Actor =>
  import ConnectionActive._
  import context.dispatcher

  def log: LoggingAdapter
  def mediator: ActorRef

  var connectionContext: Option[ConnectionContext] = None
  var transportConnection: ActorRef = _

  var closeTimeout: Option[Cancellable] = None

  // It seems socket.io client may fire heartbeat only when it received heartbeat
  // from server, or, just bounce heartheat instead of firing heartbeat standalone.
  var heartbeatHandler: Option[Cancellable] = None
  var heartbeatTimeout: Option[Cancellable] = None

  var pendingPackets = immutable.Queue[Packet]()
  var topics = immutable.Set[String]()

  val startTime = System.currentTimeMillis

  val heartbeatInterval = socketio.Settings.HeartbeatTimeout * 0.618

  def connected() {
    sendPacket(ConnectPacket())

    enableHeartbeat()
    resetHeartbeatTimeout()
  }

  def update(event: Event) = {
    event match {
      case x: Connected =>
        connectionContext = Some(new ConnectionContext(x.sessionId, x.query, x.origins))
        transportConnection = x.transportConnection
        connectionContext.foreach(_.bindTransport(x.transport))
      case x: UpdatePackets =>
        pendingPackets = immutable.Queue(x.packets: _*)
    }
  }

  def processNewConnected(conn: Connected) {
    update(conn)
    connected()
  }

  def processUpdatePackets(packets: UpdatePackets) {
    update(packets)
  }

  def working: Receive = {
    case CreateSession(_) => // may be forwarded by resolver, just ignore it.

    case conn @ Connecting(sessionId, query, origins, transportConn, transport) =>
      log.debug("Connecting request: {}", sessionId)
      disableCloseTimeout()

      connectionContext match {
        case Some(existed) =>
          transportConnection = transportConn
          existed.bindTransport(transport)
          connected()
        case None =>
          processNewConnected(Connected(conn.sessionId, conn.query, conn.origins, conn.transportConnection, conn.transport))
      }

    case OnFrame(sessionId, transportConnection, frame)  => onFrame(transportConnection, frame)
    case OnGet(sessionId, transportConnection)           => onGet(transportConnection)
    case OnPost(sessionId, transportConnection, payload) => onPost(transportConnection, payload)

    case SendMessage(sessionId, endpoint, msg)           => sendMessage(endpoint, msg)
    case SendJson(sessionId, endpoint, json)             => sendJson(endpoint, json)
    case SendEvent(sessionId, endpoint, name, args)      => sendEvent(endpoint, name, args)
    case SendPackets(sessionId, packets)                 => sendPacket(packets: _*)

    case SendAck(sessionId, packet, args)                => sendAck(packet, args)

    case Broadcast(sessionId, room, packet)              => publishToMediator(OnBroadcast(sessionId, room, packet))
    case OnBroadcast(senderSessionId, room, packet)      => sendPacket(packet) // write to client

    case Subscribe(sessionId, endpoint, room) =>
      val topic = topicFor(endpoint, room)
      topics += topic
      subscribe(topic)

    case Unsubscribe(sessionId, endpoint, room) =>
      val topic = topicFor(endpoint, room)
      topics -= topic
      unsubscribe(topic)

    case AskConnectedTime =>
      sender() ! System.currentTimeMillis - startTime
  }

  def disableHeartbeat() {
    log.debug("{}: heartbeat disabled.", self.path)
    heartbeatHandler foreach (_.cancel)
    heartbeatHandler = None
  }

  def enableHeartbeat() {
    log.debug("{}: heartbeat enabled.", self.path)
    if (heartbeatHandler.isEmpty || heartbeatHandler.nonEmpty && heartbeatHandler.get.isCancelled) {
      heartbeatHandler = Some(context.system.scheduler.schedule(0.seconds, heartbeatInterval.seconds, self, SendPackets(null, List(HeartbeatPacket))))
    }
  }

  // --- heartbeat timeout

  def resetHeartbeatTimeout() {
    heartbeatTimeout foreach (_.cancel)
    heartbeatTimeout = Some(context.system.scheduler.scheduleOnce(socketio.Settings.HeartbeatTimeout.seconds) {
      enableCloseTimeout()
    })
  }

  // --- close timeout

  def enableCloseTimeout() {
    log.debug("{}: close timeout, will close in {} seconds", self.path, socketio.Settings.CloseTimeout)
    closeTimeout foreach (_.cancel)
    if (context != null) {
      closeTimeout = Some(context.system.scheduler.scheduleOnce(socketio.Settings.CloseTimeout.seconds) {
        log.warning("{}: stoped due to close timeout", self.path)
        disableHeartbeat()
        context.stop(self)
      })
    }
  }

  def disableCloseTimeout() {
    closeTimeout foreach (_.cancel)
    closeTimeout = None
  }

  // --- reacts

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
        resetHeartbeatTimeout()

      case ConnectPacket(endpoint, args) =>
        connectionContext foreach { ctx => publishToMediator(OnPacket(packet, ctx)) }
        val topic = topicFor(endpoint, "")
        topics += topic
        subscribe(topic).onComplete {
          case Success(ack) =>
            // bounce connect packet back to client
            sendPacket(packet)
          case Failure(ex) =>
            log.warning("Failed to subscribe to medietor on topic {}: {}", topic, ex.getMessage)
        }

      case DisconnectPacket(endpoint) =>
        connectionContext foreach { ctx => publishToMediator(OnPacket(packet, ctx)) }
        val topic = topicFor(endpoint, "")
        topics -= topic
        if (endpoint == "") {
          topics foreach unsubscribe
          topics = Set()
          context.stop(self)
        } else {
          unsubscribe(topic)
        }

      case _ =>
        // for data packet that requests ack and has no ack data, automatically ack
        packet match {
          case x: DataPacket if x.isAckRequested && !x.hasAckData => sendAck(x, "[]")
          case _ =>
        }
        connectionContext foreach { ctx => publishToMediator(OnPacket(packet, ctx)) }
    }
  }

  def onFrame(transportConnection: ActorRef, frame: TextFrame) {
    disableCloseTimeout()
    onPayload(transportConnection, frame.payload)
  }

  def onGet(transportConnection: ActorRef) {
    disableCloseTimeout()
    connectionContext foreach { ctx =>
      processUpdatePackets(UpdatePackets(ctx.transport.writeSingle(ctx, transportConnection, isSendingNoopWhenEmpty = true, pendingPackets)))
    }
  }

  def onPost(transportConnection: ActorRef, payload: ByteString) {
    disableCloseTimeout()
    connectionContext foreach { ctx =>
      // response an empty entity to release POST before message processing
      ctx.transport.write(ctx, transportConnection, "")
    }
    onPayload(transportConnection, payload)
  }

  def sendMessage(endpoint: String, msg: String) {
    val packet = MessagePacket(-1L, false, endpoint, msg)
    sendPacket(packet)
  }

  def sendJson(endpoint: String, json: String) {
    val packet = JsonPacket(-1L, false, endpoint, json)
    sendPacket(packet)
  }

  def sendEvent(endpoint: String, name: String, args: Either[String, Seq[String]]) {
    val packet = args match {
      case Left(x)   => EventPacket(-1L, false, endpoint, name, x)
      case Right(xs) => EventPacket(-1L, false, endpoint, name, xs)
    }
    sendPacket(packet)
  }

  /**
   * enqueue packets, and let tranport decide if flush them or pending flush
   */
  def sendPacket(packets: Packet*) {
    var updatePendingPackets = pendingPackets
    packets foreach { packet => updatePendingPackets = updatePendingPackets.enqueue(packet) }
    log.debug("Enqueued {}, pendingPackets: {}", packets, pendingPackets)
    connectionContext foreach { ctx =>
      updatePendingPackets = ctx.transport.flushOrWait(ctx, transportConnection, updatePendingPackets)
    }
    processUpdatePackets(UpdatePackets(updatePendingPackets))
  }

  def sendAck(originalPacket: DataPacket, args: String) {
    sendPacket(AckPacket(originalPacket.id, args))
  }

  def publishToMediator(msg: Any) {
    msg match {
      case x: OnPacket[_] => mediator ! Publish(socketio.topicFor(x.packet.endpoint, ""), x)
      case x: OnBroadcast => mediator ! Publish(socketio.topicFor(x.packet.endpoint, x.room), x)
    }
  }

  def subscribe(topic: String): Future[SubscribeAck] = {
    mediator.ask(DistributedPubSubMediator.Subscribe(topic, self))(socketio.actorResolveTimeout).mapTo[SubscribeAck]
  }

  def unsubscribe(topic: String) {
    mediator ! DistributedPubSubMediator.Unsubscribe(topic, self)
  }

}

