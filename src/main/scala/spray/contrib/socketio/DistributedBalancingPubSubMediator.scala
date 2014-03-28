package spray.contrib.socketio

import akka.actor.{ ActorLogging, Actor, ActorRef, Props }
import akka.contrib.pattern.{ DistributedPubSubExtension, ClusterClient }
import akka.contrib.pattern.DistributedPubSubMediator.Publish
import akka.contrib.pattern.DistributedPubSubMediator.Subscribe
import akka.contrib.pattern.DistributedPubSubMediator.SubscribeAck
import akka.contrib.pattern.DistributedPubSubMediator.Unsubscribe
import akka.contrib.pattern.DistributedPubSubMediator.UnsubscribeAck
import akka.pattern.ask
import akka.routing.ActorRefRoutee
import akka.routing.AddRoutee
import akka.routing.GetRoutees
import akka.routing.RemoveRoutee
import akka.routing.RoundRobinGroup
import spray.contrib.socketio

object DistributedBalancingPubSubMediator {

  def props() = Props(classOf[DistributedBalancingPubSubMediator])

  case class SubscribeGroup(topic: String, group: String, ref: ActorRef)

  case class UnsubscribeGroup(topic: String, group: String, ref: ActorRef)

  case class PublishSubscribeGroup(subscribe: SubscribeGroup, origin: ActorRef)

  case class PublishUnsubscribeGroup(unsubscribe: UnsubscribeGroup, origin: ActorRef)

  val InternalTopic = "ClusterNamespaceMediatorPubSub"

}

/**
 * A mediator that can Subscribe by group and Publish to one actor each group
 *
 */
class DistributedBalancingPubSubMediator extends Actor with ActorLogging {

  import DistributedBalancingPubSubMediator._

  val pubsubMediator = DistributedPubSubExtension(context.system).mediator

  pubsubMediator ! Subscribe(InternalTopic, self)

  var topicToSubscriptions: Map[String, Map[String, ActorRef]] = Map.empty.withDefaultValue(Map.empty)

  def getSubscription(topic: String, group: String): Option[ActorRef] = {
    topicToSubscriptions(topic).get(group)
  }

  def getOrElseInsertSubscription(topic: String, group: String, subscription: ActorRef): ActorRef = {
    val opt = getSubscription(topic, group)
    if (opt.isDefined) {
      opt.get
    } else {
      //TODO use ConsistentHashingGroup
      val router = context.actorOf(RoundRobinGroup(List()).props(), topic + "-" + group)
      topicToSubscriptions += topic -> (topicToSubscriptions(topic) + (group -> router))
      router
    }
  }

  override def receive: Receive = {
    case x @ SubscribeGroup(topic, group, subscription) =>
      pubsubMediator ! Publish(InternalTopic, PublishSubscribeGroup(x, self))
      val subscriber = sender()
      val router = getOrElseInsertSubscription(topic, group, subscription)
      import context.dispatcher
      router ! AddRoutee(ActorRefRoutee(subscription))
      (router ? GetRoutees)(socketio.actorResolveTimeout) onSuccess {
        case _ => subscriber ! SubscribeAck(Subscribe(topic, subscription))
      }

    case x @ UnsubscribeGroup(topic, group, subscription) =>
      pubsubMediator ! Publish(InternalTopic, PublishUnsubscribeGroup(x, self))
      val subscriber = sender()
      val ack = UnsubscribeAck(Unsubscribe(topic, subscription))
      val opt = getSubscription(topic, group) match {
        case Some(router) =>
          router ! RemoveRoutee(ActorRefRoutee(subscription))
          import context.dispatcher
          (router ? GetRoutees)(socketio.actorResolveTimeout) onSuccess {
            case _ => subscriber ! ack
          }
        case None =>
          subscriber ! ack
      }

    case pub: PublishSubscribeGroup =>
      if (pub.origin != self) {
        self ! pub.subscribe
      }

    case pub: PublishUnsubscribeGroup =>
      if (pub.origin != self) {
        self ! pub.unsubscribe
      }

    case Publish(topic: String, msg: Any) =>
      topicToSubscriptions.get(topic) foreach (_.values foreach (_ ! msg))

    case x: SubscribeAck =>

    case x               => log.info("unhandled : " + x)
  }

}

object DistributedBalancingPubSubProxy {
  def props(path: String, group: String, client: ActorRef) = Props(classOf[DistributedBalancingPubSubProxy], path, group, client)
}

/**
 * This actor is running on the business logic nodes out of cluster
 *
 * @Note:
 * 1. Messages between cluster client and cluster nodes may be lost if client down
 *    or the node that holds the receptionist which client connect to down.
 * 2. For above condition, the business logic should decide if it needs business
 *    level transations, i.e. rollback unfinished transactions and optionally try again.
 * 3. We need to implement graceful offline logic for both cluster node and clusterclient
 *
 * @param path [[DistributedBalancingPubSubMediator]] singleton path
 * @param client [[ClusterClient]] to access Cluster
 */
class DistributedBalancingPubSubProxy(path: String, group: String, client: ActorRef) extends Actor with ActorLogging {
  override def receive: Actor.Receive = {
    case Subscribe(topic, ref) =>
      client forward ClusterClient.Send(path, DistributedBalancingPubSubMediator.SubscribeGroup(topic, group, ref), false)
    case Unsubscribe(topic, ref) =>
      client forward ClusterClient.Send(path, DistributedBalancingPubSubMediator.UnsubscribeGroup(topic, group, ref), false)
    case x: Publish => client forward ClusterClient.Send(path, x, false)
  }
}
