package spray.contrib.socketio

import akka.actor.{ Props, PoisonPill, ActorLogging, ActorRef }
import akka.persistence.{ PersistenceFailure, EventsourcedProcessor }
import akka.contrib.pattern.ShardRegion.Passivate

object PersistentConnectionActive {
  def props(namespaceMediator: ActorRef, broadcastMediator: ActorRef): Props = Props(classOf[PersistentConnectionActive], namespaceMediator, broadcastMediator)
}

class PersistentConnectionActive(val namespaceMediator: ActorRef, val broadcastMediator: ActorRef) extends ConnectionActive with EventsourcedProcessor with ActorLogging {

  import ConnectionActive._

  def receiveRecover: Receive = {
    case event: Event => update(event)
  }

  def receiveCommand: Receive = working orElse {
    case PersistenceFailure(_, _, ex) => log.error("Failed to persistence: {}", ex.getMessage)
  }

  override def processConnectingEvent(conn: ConnectingEvent) {
    persist(conn)(super.processConnectingEvent(_))
  }

  override def processSubscribeBroadcastEvent(evt: SubscribeBroadcastEvent) {
    persist(evt)(super.processSubscribeBroadcastEvent(_))
  }

  override def processUnsubscribeBroadcastEvent(evt: UnsubscribeBroadcastEvent) {
    persist(evt)(super.processUnsubscribeBroadcastEvent(_))
  }

  override def close() {
    context.parent ! Passivate(stopMessage = PoisonPill)
  }
}
