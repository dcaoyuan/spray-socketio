package spray.contrib.socketio

import akka.actor.{ Props, ActorLogging, ActorRef }
import akka.persistence.{ PersistenceFailure, PersistentActor, SaveSnapshotSuccess, SaveSnapshotFailure, SnapshotOffer, RecoveryCompleted }

object PersistentConnectionSession {
  def props(mediator: ActorRef): Props = Props(classOf[PersistentConnectionSession], mediator)
}

final class PersistentConnectionSession(val mediator: ActorRef) extends ConnectionSession with PersistentActor with ActorLogging {

  override def persistenceId = self.path.toStringWithoutAddress

  def receiveRecover: Receive = {
    case event: ConnectionSession.Event => working(event)

    case SnapshotOffer(metadata, offeredSnapshot: ConnectionSession.State) =>
      log.info("Recovering from offeredSnapshot: {}", offeredSnapshot)
      state = offeredSnapshot
      state.topics foreach subscribeBroadcast

    case x: SnapshotOffer => log.warning("Recovering received unknown: {}", x)

    case RecoveryCompleted =>
      if (state.context.isConnected) {
        // shall we enable heartbeat now, or, just wait for the client to reconnect or send a heartbeat ?
        //enableHeartbeat()
      }
  }

  def receiveCommand: Receive = working orElse {
    case SaveSnapshotSuccess(_)           =>
    case SaveSnapshotFailure(_, reason)   => log.error("Failed to save snapshot: {}", reason)
    case PersistenceFailure(_, _, reason) => log.error("Failed to persistence: {}", reason)
  }

  override def updateState(evt: Any, newState: ConnectionSession.State) {
    super.updateState(evt, newState)
    if (recoveryFinished) {
      saveSnapshot(state)
    }
  }

}
