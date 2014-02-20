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

object SocketIOConnection {
  case object ConnectedTime

  case object Pause
  final case class ProcessingWith(soContext: SocketIOContext)

  final case class SendMessage(message: String)(implicit val endpoint: String)
  final case class SendJson(json: JsValue)(implicit val endpoint: String)
  final case class SendEvent(name: String, args: List[JsValue])(implicit val endpoint: String)
  final case class SendPacket(packet: Packet)
}

class SocketIOConnection(namespaces: ActorRef) extends Actor with Stash with ActorLogging {
  import SocketIOConnection._
  import context.dispatcher

  val startTime = System.currentTimeMillis

  def heartbeatInterval = socketio.heartbeatTimeout * 0.618

  private var _soContext: SocketIOContext = _
  private def soContext = _soContext

  private var _transportActor: ActorRef = _
  def transportActor = _transportActor
  private def transportActor_=(transportActor: ActorRef) {
    _transportActor = transportActor
  }

  // It seems socket.io client may fire heartbeat only when it received heartbeat
  // from server, or, just bounce heartheat instead of firing heartbeat standalone.
  var heartbeatFiring: Option[Cancellable] = None
  var heartbeatTimeout: Option[Cancellable] = None

  def attachContext(soContext: SocketIOContext) {
    _soContext = soContext
    transportActor = _soContext.transportActor
  }

  def receive = paused

  def paused: Receive = {
    case ProcessingWith(soContext) =>
      attachContext(soContext)
      heartbeatFiring = Some(context.system.scheduler.schedule(0.seconds, heartbeatInterval.seconds) {
        send(HeartbeatPacket)
      })
      heartbeatTimeout = Some(context.system.scheduler.scheduleOnce((socketio.heartbeatTimeout).seconds) {
        namespaces ! Namespace.HeartbeatTimeout(transportActor)
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
        namespaces ! Namespace.HeartbeatTimeout(transportActor)
      })

    case ConnectedTime =>
      sender() ! System.currentTimeMillis - startTime

    case x @ SendMessage(message)  => sendMessage(message)(x.endpoint)
    case x @ SendJson(json)        => sendJson(json)(x.endpoint)
    case x @ SendEvent(name, args) => sendEvent(name, args)(x.endpoint)
    case x @ SendPacket(packet)    => send(packet)
  }

  private def properEndpoint(endpoint: String) = if (endpoint == Namespace.DEFAULT_NAMESPACE) "" else endpoint

  private def sendMessage(msg: String)(implicit endpoint: String) {
    val packet = MessagePacket(-1L, false, properEndpoint(endpoint), msg)
    send(packet)
  }

  private def sendJson(json: JsValue)(implicit endpoint: String) {
    val packet = JsonPacket(-1L, false, properEndpoint(endpoint), json)
    send(packet)
  }

  private def sendEvent(name: String, args: List[JsValue])(implicit endpoint: String) {
    val packet = EventPacket(-1L, false, properEndpoint(endpoint), name, args)
    send(packet)
  }

  private def send(packet: Packet) {
    soContext.transport.send(packet, transportActor)
  }

}

