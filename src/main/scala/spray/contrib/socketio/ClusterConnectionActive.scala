package spray.contrib.socketio

import akka.actor.{ Actor, ActorLogging, ActorRef }
import akka.persistence.{ PersistenceFailure, EventsourcedProcessor }
import akka.contrib.pattern.ClusterClient

/**
 * transportConnection <1..n--1> connectionActive <1--1> connContext <1--n> transport
 *
 * @param namespaceMediator mediator for namespace nodes out of the cluster
 * @param broadcastMediator mediator for broadcast msg in the endpoint/room
 */
class ClusterConnectionActive(val namespaceMediator: ActorRef, val broadcastMediator: ActorRef) extends ConnectionActive with EventsourcedProcessor with ActorLogging {
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

/**
 * The proxy actor is running on the namespace nodes to forward msg to ConnectionActive
 *
 * @param path ConnectionActive sharding service's path
 * @param client [[ClusterClient]] to access SocketIO Cluster
 */
class ClusterConnectionActiveResolverProxy(path: String, client: ActorRef) extends Actor with ActorLogging {
  override def receive: Actor.Receive = {
    case cmd: ConnectionActive.Command => client forward ClusterClient.Send(path, cmd, false)
  }
}

