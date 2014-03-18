package spray.contrib.socketio

import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.persistence.{ PersistenceFailure, EventsourcedProcessor }

/**
 *
 * transportConnection <1..n--1> connectionActive <1--1> connContext <1--n> transport
 */
class ClusterConnectionActive(val mediator: ActorRef) extends ConnectionActive with EventsourcedProcessor with ActorLogging {
  import ConnectionActive._

  // have to call after log created
  enableCloseTimeout()

  def receiveRecover: Receive = {
    case event: Event => update(event)
  }

  def receiveCommand: Receive = working orElse {
    case PersistenceFailure(_, _, ex) => log.error("Failed to persistence: {}", ex.getMessage)
  }

  override def processNewConnected(conn: Connected) {
    persist(conn)(super.processNewConnected(_))
  }

  override def processUpdatePackets(packets: UpdatePackets) {
    if (packets.packets.isEmpty && pendingPackets.isEmpty) {
      super.processUpdatePackets(packets)
    } else {
      persist(packets)(super.processUpdatePackets(_))
    }
  }

}

