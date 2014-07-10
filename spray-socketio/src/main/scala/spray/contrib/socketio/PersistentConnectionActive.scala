package spray.contrib.socketio

import akka.actor.{ Props, ActorLogging, ActorRef }
import akka.persistence.{ PersistenceFailure, EventsourcedProcessor, SaveSnapshotSuccess, SaveSnapshotFailure, SnapshotOffer }

object PersistentConnectionActive {
  def props(namespaceMediator: ActorRef, broadcastMediator: ActorRef): Props = Props(classOf[PersistentConnectionActive], namespaceMediator, broadcastMediator)
}

class PersistentConnectionActive(val namespaceMediator: ActorRef, val broadcastMediator: ActorRef) extends ConnectionActive with EventsourcedProcessor with ActorLogging {

  import ConnectionActive._

  def receiveRecover: Receive = {
    case event: Event =>
      isReplaying = true
      working(event)
      isReplaying = false
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

  override def updateState(evt: Any, state: State) {
    super.updateState(evt, state)
    if (!isReplaying) {
      saveSnapshot(state)
    }
  }

}
