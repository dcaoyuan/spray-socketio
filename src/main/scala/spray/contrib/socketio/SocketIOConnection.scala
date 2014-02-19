package spray.contrib.socketio

import akka.actor.Actor
import akka.actor.Cancellable
import akka.actor.Stash
import akka.actor.Terminated
import akka.io.Tcp
import scala.concurrent.duration._
import spray.contrib.socketio.packet.HeartbeatPacket
import spray.contrib.socketio.packet.Packet
import spray.json.JsValue

object SocketIOConnection {
  case object ReclockHeartbeatTimeout
  case object ReclockCloseTimeout
  case object ConnectedTime
  case object Pause
  case object Resume

  final case class ReplyMessage(message: String)(implicit val endpoint: String)
  final case class ReplyJson(json: JsValue)(implicit val endpoint: String)
  final case class ReplyEvent(name: String, args: List[JsValue])(implicit val endpoint: String)
  final case class Reply(packet: Packet)(implicit val endpoint: String)
}

class SocketIOConnection(soContext: SocketIOContext) extends Actor with Stash {
  import SocketIOConnection._
  import context.dispatcher

  val startTime = System.currentTimeMillis

  val transportActor = soContext.transportActor
  var closeTimeout: Cancellable = _
  var heartbeatTimeout: Cancellable = _

  context.watch(sender)

  context.system.scheduler.schedule(5.seconds, 10.seconds) {
    soContext.send(HeartbeatPacket)("")
  }

  heartbeatTimeout = context.system.scheduler.scheduleOnce((30 + 1).seconds) {
    soContext.transportActor ! Tcp.Close
  }

  def receive = processing

  def processing: Receive = {
    case Terminated(`transportActor`) =>
      context.stop(self)

    case Pause => context.become(paused)

    case ReclockCloseTimeout =>
      if (closeTimeout != null) closeTimeout.cancel
      closeTimeout = context.system.scheduler.scheduleOnce((60 + 1).seconds) {
        soContext.transportActor ! Tcp.Close
      }

    case ReclockHeartbeatTimeout =>
      heartbeatTimeout.cancel
      heartbeatTimeout = context.system.scheduler.scheduleOnce((30 + 1).seconds) {
        soContext.transportActor ! Tcp.Close
      }

    case ConnectedTime =>
      sender() ! System.currentTimeMillis - startTime

    case x @ ReplyMessage(message) =>
      soContext.sendMessage(message)(x.endpoint)

    case x @ ReplyJson(json) =>
      soContext.sendJson(json)(x.endpoint)

    case x @ ReplyEvent(name, args) =>
      soContext.sendEvent(name, args)(x.endpoint)

    case x @ Reply(packet) =>
      soContext.send(packet)(x.endpoint)
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

