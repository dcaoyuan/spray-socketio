package spray.contrib.socketio

import akka.actor.{ Props, ActorRef, ActorLogging, Actor }

object TransientConnectionSession {
  def props(mediator: ActorRef): Props = Props(classOf[TransientConnectionSession], mediator)
}

final class TransientConnectionSession(val mediator: ActorRef) extends ConnectionSession with Actor with ActorLogging {
  def recoveryFinished: Boolean = true
  def recoveryRunning: Boolean = false

  def receive: Receive = working
}
