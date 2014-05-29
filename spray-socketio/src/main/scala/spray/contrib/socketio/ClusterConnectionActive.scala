package spray.contrib.socketio

import akka.actor._
import akka.persistence.EventsourcedProcessor
import akka.persistence.PersistenceFailure
import akka.contrib.pattern.ShardRegion.Passivate

object ClusterConnectionActive {
  def props(namespaceMediator: ActorRef, broadcastMediator: ActorRef, enableConnPersistence: Boolean) = {
    if (!enableConnPersistence) {
      Props(classOf[ClusterConnectionActive], namespaceMediator, broadcastMediator)
    } else {
      Props(classOf[PersistentClusterConnectionActive], namespaceMediator, broadcastMediator)
    }
  }
}

/**
 * transportConnection <1..n--1> connectionActive <1--1> connContext <1--n> transport
 *
 * @param namespaceMediator mediator for namespace nodes out of the cluster
 * @param broadcastMediator mediator for broadcast msg in the endpoint/room
 */
class ClusterConnectionActive(val namespaceMediator: ActorRef, val broadcastMediator: ActorRef) extends ConnectionActive with Actor with ActorLogging {
  override def receive: Actor.Receive = working
}

class PersistentClusterConnectionActive(val namespaceMediator: ActorRef, val broadcastMediator: ActorRef) extends ConnectionActive with EventsourcedProcessor with ActorLogging {

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
