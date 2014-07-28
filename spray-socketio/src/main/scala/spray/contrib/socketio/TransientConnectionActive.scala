package spray.contrib.socketio

import akka.actor.{ Props, ActorRef, ActorLogging, Actor }

object TransientConnectionActive {
  def props(namespaceMediator: ActorRef, broadcastMediator: ActorRef): Props = Props(classOf[TransientConnectionActive], namespaceMediator, broadcastMediator)
}

final class TransientConnectionActive(val namespaceMediator: ActorRef, val broadcastMediator: ActorRef) extends ConnectionActive with Actor with ActorLogging {
  def recoveryFinished: Boolean = true
  def recoveryRunning: Boolean = false

  def receive: Receive = working
}
