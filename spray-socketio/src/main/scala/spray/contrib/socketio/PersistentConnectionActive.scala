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
      working(event)

    case SnapshotOffer(metadata, offeredSnapshot: State) =>
      log.info("Recovering from offeredSnapshot: {}", offeredSnapshot)
      state = offeredSnapshot
      state.topics foreach subscribeBroadcast

    case x: SnapshotOffer => log.warning("Recovering received unknown: {}", x)
  }

  def receiveCommand: Receive = working orElse {
    case SaveSnapshotSuccess(_)           =>
    case SaveSnapshotFailure(_, reason)   => log.error("Failed to save snapshot: {}", reason)
    case PersistenceFailure(_, _, reason) => log.error("Failed to persistence: {}", reason)
  }

  override def updateState(evt: Any, newState: State) {
    super.updateState(evt, newState)
    if (recoveryFinished) {
      saveSnapshot(state)
    }
  }

}
