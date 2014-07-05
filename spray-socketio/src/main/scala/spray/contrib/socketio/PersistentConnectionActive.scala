package spray.contrib.socketio

import akka.actor.{ Props, PoisonPill, ActorLogging, ActorRef }
import akka.persistence.{ PersistenceFailure, EventsourcedProcessor, SaveSnapshotSuccess, SaveSnapshotFailure, SnapshotOffer }
import akka.contrib.pattern.ShardRegion.Passivate

object PersistentConnectionActive {
  def props(namespaceMediator: ActorRef, broadcastMediator: ActorRef): Props = Props(classOf[PersistentConnectionActive], namespaceMediator, broadcastMediator)
}

class PersistentConnectionActive(val namespaceMediator: ActorRef, val broadcastMediator: ActorRef) extends ConnectionActive with EventsourcedProcessor with ActorLogging {

  import ConnectionActive._

  def receiveRecover: Receive = {
    case event: Event => //update(event)
    case SnapshotOffer(_, offeredSnapshot: State) =>
      log.debug("Got snapshotoffer: {}", offeredSnapshot)
      state = offeredSnapshot
      state.topics foreach subscribeBroadcast
  }

  def receiveCommand: Receive = working orElse {
    case SaveSnapshotSuccess(_)           =>
    case SaveSnapshotFailure(_, reason)   => log.error("Failed to save snapshot: {}", reason)
    case PersistenceFailure(_, _, reason) => log.error("Failed to persistence: {}", reason)
  }

  override def processConnectingEvent(evt: Connecting) {
    super.processConnectingEvent(evt)
    saveSnapshot(state)
  }

  override def processSubscribeBroadcastEvent(evt: SubscribeBroadcast) {
    super.processSubscribeBroadcastEvent(_)
    saveSnapshot(state)
  }

  override def processUnsubscribeBroadcastEvent(evt: UnsubscribeBroadcast) {
    super.processUnsubscribeBroadcastEvent(evt)
    saveSnapshot(state)
  }

  override def close() {
    context.parent ! Passivate(stopMessage = PoisonPill)
  }
}
