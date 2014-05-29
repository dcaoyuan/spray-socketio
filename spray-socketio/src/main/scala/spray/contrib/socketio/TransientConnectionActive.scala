package spray.contrib.socketio

import akka.actor.{ Props, ActorRef, ActorLogging, Actor }

object TransientConnectionActive {
  def props(namespaceMediator: ActorRef, broadcastMediator: ActorRef): Props = Props(classOf[TransientConnectionActive], namespaceMediator, broadcastMediator)
}

class TransientConnectionActive(val namespaceMediator: ActorRef, val broadcastMediator: ActorRef) extends ConnectionActive with Actor with ActorLogging {
  override def receive: Receive = working
}
