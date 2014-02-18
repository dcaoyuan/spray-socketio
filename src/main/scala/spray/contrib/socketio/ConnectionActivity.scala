package spray.contrib.socketio

import akka.actor.Actor
import akka.actor.Cancellable
import akka.actor.Terminated
import akka.io.Tcp
import scala.concurrent.duration._
import spray.contrib.socketio.packet.HeartbeatPacket

object ConnectionActivity {
  case object ReclockHeartbeatTimeout
  case object ReclockCloseTimeout
  case object ConnectedTime
}

class ConnectionActivity(conn: SocketIOConnection) extends Actor {
  import context.dispatcher
  import ConnectionActivity._

  val startTime = System.currentTimeMillis

  val connActor = conn.connActor
  var closeTimeout: Cancellable = _
  var heartbeatTimeout: Cancellable = _

  context.watch(sender)

  context.system.scheduler.schedule(5.seconds, 10.seconds) {
    conn.send(HeartbeatPacket)("")
  }

  heartbeatTimeout = context.system.scheduler.scheduleOnce((30 + 1).seconds) {
    conn.connActor ! Tcp.Close
  }

  def receive = {
    case Terminated(`connActor`) =>
      context.stop(self)

    case ReclockCloseTimeout =>
      if (closeTimeout != null) closeTimeout.cancel
      closeTimeout = context.system.scheduler.scheduleOnce((60 + 1).seconds) {
        conn.connActor ! Tcp.Close
      }

    case ReclockHeartbeatTimeout =>
      heartbeatTimeout.cancel
      heartbeatTimeout = context.system.scheduler.scheduleOnce((30 + 1).seconds) {
        conn.connActor ! Tcp.Close
      }

    case ConnectedTime =>
      sender() ! System.currentTimeMillis - startTime
  }
}

