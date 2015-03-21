package spray.contrib.socketio

import akka.actor.{ Actor, ActorLogging, Props }

object TransientConnectionSession {
  def props(): Props = Props(classOf[TransientConnectionSession])
}

final class TransientConnectionSession() extends ConnectionSession with Actor with ActorLogging {
  def recoveryFinished: Boolean = true
  def recoveryRunning: Boolean = false

  def receive: Receive = working
}
