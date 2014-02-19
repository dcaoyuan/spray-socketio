package spray.contrib.socketio

import akka.actor.Actor
import akka.actor.Cancellable
import akka.actor.Terminated
import akka.io.Tcp
import scala.concurrent.duration._
import spray.contrib.socketio.packet.HeartbeatPacket

object SocketIOConnection {
  case object ReclockHeartbeatTimeout
  case object ReclockCloseTimeout
  case object ConnectedTime
}

class SocketIOConnection(soContext: SocketIOContext) extends Actor {
  import context.dispatcher
  import SocketIOConnection._

  val startTime = System.currentTimeMillis

  val connActor = soContext.connActor
  var closeTimeout: Cancellable = _
  var heartbeatTimeout: Cancellable = _

  context.watch(sender)

  context.system.scheduler.schedule(5.seconds, 10.seconds) {
    soContext.send(HeartbeatPacket)("")
  }

  heartbeatTimeout = context.system.scheduler.scheduleOnce((30 + 1).seconds) {
    soContext.connActor ! Tcp.Close
  }

  def receive = {
    case Terminated(`connActor`) =>
      context.stop(self)

    case ReclockCloseTimeout =>
      if (closeTimeout != null) closeTimeout.cancel
      closeTimeout = context.system.scheduler.scheduleOnce((60 + 1).seconds) {
        soContext.connActor ! Tcp.Close
      }

    case ReclockHeartbeatTimeout =>
      heartbeatTimeout.cancel
      heartbeatTimeout = context.system.scheduler.scheduleOnce((30 + 1).seconds) {
        soContext.connActor ! Tcp.Close
      }

    case ConnectedTime =>
      sender() ! System.currentTimeMillis - startTime
  }
}

