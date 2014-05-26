package spray.contrib.socketio

import akka.actor._
import akka.persistence.EventsourcedProcessor
import akka.persistence.PersistenceFailure
import akka.contrib.pattern.ShardRegion.Passivate

object ClusterConnectionActive {
  def props(namespaceMediator: ActorRef, broadcastMediator: ActorRef, enableConnPersistence: Boolean) = Props(classOf[ClusterConnectionActive], namespaceMediator, broadcastMediator, enableConnPersistence)
}

/**
 * transportConnection <1..n--1> connectionActive <1--1> connContext <1--n> transport
 *
 * @param namespaceMediator mediator for namespace nodes out of the cluster
 * @param broadcastMediator mediator for broadcast msg in the endpoint/room
 */
class ClusterConnectionActive(val namespaceMediator: ActorRef, val broadcastMediator: ActorRef, val enableConnPersistence: Boolean) extends ConnectionActive with EventsourcedProcessor with ActorLogging {
  import ConnectionActive._

  def receiveRecover: Receive = {
    case event: Event => update(event)
  }

  def receiveCommand: Receive = working orElse {
    case PersistenceFailure(_, _, ex) => log.error("Failed to persistence: {}", ex.getMessage)
  }

  override def processConnectingEvent(conn: ConnectingEvent) {
    if (enableConnPersistence) {
      persist(conn)(super.processConnectingEvent(_))
    } else {
      super.processConnectingEvent(conn)
    }
  }

  override def processSubscribeBroadcastEvent(evt: SubscribeBroadcastEvent) {
    if (enableConnPersistence) {
      persist(evt)(super.processSubscribeBroadcastEvent(_))
    } else {
      super.processSubscribeBroadcastEvent(evt)
    }
  }

  override def processUnsubscribeBroadcastEvent(evt: UnsubscribeBroadcastEvent) {
    if (enableConnPersistence) {
      persist(evt)(super.processUnsubscribeBroadcastEvent(_))
    } else {
      super.processUnsubscribeBroadcastEvent(evt)
    }
  }

  override def close() {
    context.parent ! Passivate(stopMessage = PoisonPill)
  }
}

