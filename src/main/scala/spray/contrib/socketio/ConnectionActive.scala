package spray.contrib.socketio

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Cancellable
import akka.actor.Props
import akka.actor.ReceiveTimeout
import akka.util.ByteString
import org.parboiled2.ParseError
import scala.collection.immutable
import scala.concurrent.Future
import scala.concurrent.duration._
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

  final case class OnPayload(transportConnection: ActorRef, payload: ByteString)
  final case class OnGet(transportConnection: ActorRef)
  final case class OnPost(transportConnection: ActorRef, payload: ByteString)

  def actorPath(sessionId: String) = "/user/" + sessionId

  def selectOrCreateConnectionActive(system: ActorSystem, sessionId: String): Future[ActorRef] = {
    import system.dispatcher
    system.actorSelection(actorPath(sessionId)).resolveOne(5.seconds).recover {
      case _: Throwable => system.actorOf(Props(classOf[ConnectionActive]), name = sessionId)
    }
  }

  def selectConnectionActive(system: ActorSystem, sessionId: String): Future[ActorRef] = {
    import system.dispatcher
    system.actorSelection(actorPath(sessionId)).resolveOne(5.seconds)
  }
}

/**
 *
 * connectionActive <1--1> connContext <1--n> transport <1--1..n> serverConnection
 */
class ConnectionActive extends Actor with ActorLogging {
  import ConnectionActive._
  import context.dispatcher

  context.setReceiveTimeout(socketio.Settings.CloseTimeout.seconds)

  var connectionContext: Option[ConnectionContext] = None
  var transportConnection: ActorRef = _

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

      enqueueAndMaySendPacket(ConnectPacket())

      if (heartbeatHandler.isEmpty) {
        heartbeatHandler = Some(context.system.scheduler.schedule(0.seconds, heartbeatInterval.seconds) {
          enqueueAndMaySendPacket(HeartbeatPacket)
          sendPacket(HeartbeatPacket)
        })
      }

      heartbeatTimeout foreach (_.cancel)
      heartbeatTimeout = Some(context.system.scheduler.scheduleOnce(socketio.Settings.HeartbeatTimeout.seconds) {
        context.setReceiveTimeout(socketio.Settings.CloseTimeout.seconds)
      })

    case Pause =>
      log.debug("{}: paused.", self.path)
      heartbeatHandler foreach (_.cancel)
      heartbeatHandler = None

    case ReceiveTimeout =>
      log.debug("{} stoped dure to ReceiveTimeout", self.path)
      context.stop(self)

    case OnPayload(transportConnection, payload) => onPayload(transportConnection, payload)
    case OnGet(payload)                          => onGet(payload)
    case OnPost(transportConnection, payload)    => onPost(transportConnection, payload)

    case SendMessage(msg, endpoint)              => sendMessage(msg, endpoint)
    case SendJson(json, endpoint)                => sendJson(json, endpoint)
    case SendEvent(name, args, endpoint)         => sendEvent(name, args, endpoint)
    case SendPackets(packets)                    => enqueueAndMaySendPacket(packets: _*)

    case AskConnectedTime =>
      sender() ! System.currentTimeMillis - startTime
  }

  def onPayload(transportConnection: ActorRef, payload: ByteString) {
    try {
      PacketParser(payload) foreach onPacket
    } catch {
      case ex: ParseError => log.warning("Error in parsing packet: {}", ex.formatTraces)
    }
  }

  def onPacket(packet: Packet) {
    packet match {
      case HeartbeatPacket =>
        heartbeatTimeout foreach (_.cancel)
        heartbeatTimeout = Some(context.system.scheduler.scheduleOnce(socketio.Settings.HeartbeatTimeout.seconds) {
          context.setReceiveTimeout(socketio.Settings.CloseTimeout.seconds)
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

  def onGet(transportConnection: ActorRef) {
    connectionContext foreach { ctx =>
      pendingPackets = ctx.transport.writeSingle(ctx, transportConnection, isSendingNoopWhenEmpty = true, pendingPackets)
    }
  }

  def onPost(transportConnection: ActorRef, payload: ByteString) {
    // response an empty entity to release POST before message processing
    connectionContext foreach { ctx =>
      ctx.transport.write(ctx, transportConnection, "")
    }
    onPayload(transportConnection, payload)
  }

  private def properEndpoint(endpoint: String) = if (endpoint == Namespace.DEFAULT_NAMESPACE) "" else endpoint

  def sendMessage(msg: String, endpoint: String) {
    val packet = MessagePacket(-1L, false, properEndpoint(endpoint), msg)
    enqueueAndMaySendPacket(packet)
  }

  def sendJson(json: JsValue, endpoint: String) {
    val packet = JsonPacket(-1L, false, properEndpoint(endpoint), json)
    enqueueAndMaySendPacket(packet)
  }

  def sendEvent(name: String, args: List[JsValue], endpoint: String) {
    val packet = EventPacket(-1L, false, properEndpoint(endpoint), name, args)
    enqueueAndMaySendPacket(packet)
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

