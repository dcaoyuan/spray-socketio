package spray.contrib.socketio

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.Cancellable
import akka.actor.Stash
import akka.actor.Terminated
import akka.io.Tcp
import scala.concurrent.duration._
import spray.contrib.socketio
import spray.contrib.socketio.packet.EventPacket
import spray.contrib.socketio.packet.HeartbeatPacket
import spray.contrib.socketio.packet.JsonPacket
import spray.contrib.socketio.packet.MessagePacket
import spray.contrib.socketio.packet.Packet
import spray.json.JsValue

object SocketIOConnection {
  case object ReclockHeartbeatTimeout
  case object ReclockCloseTimeout
  case object ConnectedTime
  case object Pause
  case object Resume

  final case class SendMessage(message: String)(implicit val endpoint: String)
  final case class SendJson(json: JsValue)(implicit val endpoint: String)
  final case class SendEvent(name: String, args: List[JsValue])(implicit val endpoint: String)
  final case class SendPacket(packet: Packet)
}

class SocketIOConnection(soContext: SocketIOContext) extends Actor with Stash with ActorLogging {
  import SocketIOConnection._
  import context.dispatcher

  val startTime = System.currentTimeMillis

  val transportActor = soContext.transportActor
  var closeTimeout: Cancellable = _
  var heartbeatTimeout: Cancellable = _

  context.watch(sender)

  context.system.scheduler.schedule(1.seconds, socketio.heartbeatTimeout.seconds) {
    send(HeartbeatPacket)
  }

  heartbeatTimeout = context.system.scheduler.scheduleOnce((socketio.heartbeatTimeout + 1).seconds) {
    transportActor ! Tcp.Close
  }

  def receive = processing

  def processing: Receive = {
    case Terminated(`transportActor`) => context.stop(self)
    case Pause                        => context.become(paused)

    case ReclockCloseTimeout =>
      if (closeTimeout != null) closeTimeout.cancel
      closeTimeout = context.system.scheduler.scheduleOnce(socketio.closeTimeout.seconds) {
        transportActor ! Tcp.Close
      }

    case ReclockHeartbeatTimeout =>
      log.info("Got heartbeat!")
      heartbeatTimeout.cancel
      heartbeatTimeout = context.system.scheduler.scheduleOnce((socketio.heartbeatTimeout + 1).seconds) {
        log.info("Disconnected due to heartbeat timeout.")
        transportActor ! Tcp.Close
      }

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

  def paused: Receive = {
    case Terminated(`transportActor`) =>
      context.stop(self)

    case Resume =>
      unstashAll()
      context.become(processing)

    case msg =>
      stash()
  }
}

