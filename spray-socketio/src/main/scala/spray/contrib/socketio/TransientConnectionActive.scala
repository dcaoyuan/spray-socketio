package spray.contrib.socketio

import akka.actor.{ Props, ActorRef, ActorLogging, Actor }

object TransientConnectionSession {
  def props(namespaceMediator: ActorRef, broadcastMediator: ActorRef): Props = Props(classOf[TransientConnectionSession], namespaceMediator, broadcastMediator)
}

final class TransientConnectionSession(val namespaceMediator: ActorRef, val broadcastMediator: ActorRef) extends ConnectionSession with Actor with ActorLogging {
  def recoveryFinished: Boolean = true
  def recoveryRunning: Boolean = false

  def receive: Receive = working
}
