package spray.contrib.socketio

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.Cancellable
import akka.actor.Stash
import scala.concurrent.duration._
import spray.contrib.socketio
import spray.contrib.socketio.packet.EventPacket
import spray.contrib.socketio.packet.HeartbeatPacket
import spray.contrib.socketio.packet.JsonPacket
import spray.contrib.socketio.packet.MessagePacket
import spray.contrib.socketio.packet.Packet
import spray.json.JsValue

object ConnectionActive {
  case object ConnectedTime

  case object Pause
  final case class ProcessingWith(soContext: SocketIOContext)

  final case class SendMessage(message: String, endpoint: String)
  final case class SendJson(json: JsValue, endpoint: String)
  final case class SendEvent(name: String, args: List[JsValue], endpoint: String)
  final case class SendPackets(packets: List[Packet])
}

class ConnectionActive(namespaces: ActorRef) extends Actor with Stash with ActorLogging {
  import ConnectionActive._
  import context.dispatcher

  val startTime = System.currentTimeMillis

  def heartbeatInterval = socketio.heartbeatTimeout * 0.618

  private var _soContext: SocketIOContext = _
  private def soContext = _soContext

  private var _serverConnection: ActorRef = _
  def serverConnection = _serverConnection
  private def serverConnection_=(serverConnection: ActorRef) {
    _serverConnection = serverConnection
  }

  // It seems socket.io client may fire heartbeat only when it received heartbeat
  // from server, or, just bounce heartheat instead of firing heartbeat standalone.
  var heartbeatFiring: Option[Cancellable] = None
  var heartbeatTimeout: Option[Cancellable] = None

  def receive = paused

  def paused: Receive = {
    case ProcessingWith(soContext) =>
      _soContext = soContext
      serverConnection = _soContext.serverConnection

      heartbeatFiring = Some(context.system.scheduler.schedule(0.seconds, heartbeatInterval.seconds) {
        send(List(HeartbeatPacket))
      })
      heartbeatTimeout = Some(context.system.scheduler.scheduleOnce((socketio.heartbeatTimeout).seconds) {
        namespaces ! Namespace.HeartbeatTimeout(serverConnection)
      })

      unstashAll()
      context.become(processing)

    case msg =>
      stash()
  }

  def processing: Receive = {
    case Pause =>
      heartbeatFiring foreach (_.cancel)
      context.become(paused)

    case HeartbeatPacket =>
      heartbeatTimeout foreach (_.cancel)
      heartbeatTimeout = Some(context.system.scheduler.scheduleOnce((socketio.heartbeatTimeout).seconds) {
        namespaces ! Namespace.HeartbeatTimeout(serverConnection)
      })

    case ConnectedTime =>
      sender() ! System.currentTimeMillis - startTime

    case SendMessage(message, endpoint)  => sendMessage(message, endpoint)
    case SendJson(json, endpoint)        => sendJson(json, endpoint)
    case SendEvent(name, args, endpoint) => sendEvent(name, args, endpoint)
    case SendPackets(packets)            => send(packets)
  }

  private def properEndpoint(endpoint: String) = if (endpoint == Namespace.DEFAULT_NAMESPACE) "" else endpoint

  private def sendMessage(msg: String, endpoint: String) {
    val packet = MessagePacket(-1L, false, properEndpoint(endpoint), msg)
    send(List(packet))
  }

  private def sendJson(json: JsValue, endpoint: String) {
    val packet = JsonPacket(-1L, false, properEndpoint(endpoint), json)
    send(List(packet))
  }

  private def sendEvent(name: String, args: List[JsValue], endpoint: String) {
    val packet = EventPacket(-1L, false, properEndpoint(endpoint), name, args)
    send(List(packet))
  }

  private def send(packets: List[Packet]) {
    soContext.transport.send(serverConnection, packets)(Nil) // TODO origins
  }

}

