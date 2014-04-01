package spray.contrib.socketio

import akka.actor._
import akka.contrib.pattern.{ ClusterSingletonProxy, ClusterSingletonManager, DistributedPubSubExtension }
import akka.contrib.pattern.DistributedPubSubMediator.Publish
import akka.contrib.pattern.DistributedPubSubMediator.Unsubscribe
import akka.contrib.pattern.DistributedPubSubMediator.UnsubscribeAck
import akka.contrib.pattern.DistributedPubSubMediator.Subscribe
import akka.contrib.pattern.DistributedPubSubMediator.SubscribeAck
import akka.routing.RoutingLogic
import akka.routing.Router
import akka.routing.ActorRefRoutee

object DistributedBalancingPubSubMediator {

  def props(role: Option[String], routingLogic: RoutingLogic) = Props(classOf[DistributedBalancingPubSubMediator], role, routingLogic)

  case class SubscribeGroup(topic: String, group: String, ref: ActorRef)

  case class UnsubscribeGroup(topic: String, group: String, ref: ActorRef)

  private[socketio] object Internal {

    case class PublishSubscribeGroup(subscribe: SubscribeGroup, origin: ActorRef)

    case class PublishUnsubscribeGroup(unsubscribe: UnsubscribeGroup, origin: ActorRef)

    case object GetSubscriptions

    case class GetSubscriptionsAck(subscriptions: Seq[Subscription])

    case class Subscription(topic: String, group: String, ref: ActorRef)

    val InternalTopic = "DistributedBalancingPubSubMediatorInternalTopic"
  }

}

/**
 * A mediator that can Subscribe by group and Publish to one actor each group
 *
 */
class DistributedBalancingPubSubMediator(role: Option[String], routingLogic: RoutingLogic) extends Actor with Stash with ActorLogging {

  import DistributedBalancingPubSubMediator._
  import DistributedBalancingPubSubMediator.Internal._
  import DistributedBalancingPubSubCoordinator._

  val router = Router(routingLogic)

  var subscriptions: Set[ActorRef] = Set.empty

  var topicToSubscriptions: Map[String, Map[String, Set[ActorRefRoutee]]] = Map.empty.withDefaultValue(Map.empty.withDefaultValue(Set.empty))

  val pubsubMediator = DistributedPubSubExtension(context.system).mediator

  var coordinator: ActorRef = _

  override def preStart(): Unit = {
    context.actorOf(ClusterSingletonManager.props(
      singletonProps = DistributedBalancingPubSubCoordinator.props(self),
      singletonName = "active",
      terminationMessage = PoisonPill,
      role = role),
      name = "coordinator")

    coordinator = context.actorOf(ClusterSingletonProxy.props(self.path.toStringWithoutAddress + "/coordinator/active", role), name = "coordinatorProxy")

    pubsubMediator ! Subscribe(InternalTopic, self)
    coordinator ! MediatorRegister(self)
  }

  override def postStop(): Unit = {
    pubsubMediator ! Unsubscribe(InternalTopic, self)
  }

  def existSubscription(subscription: ActorRef) = {
    topicToSubscriptions.exists {
      case (topic, groups) => groups.exists {
        case (group, refs) => refs.contains(ActorRefRoutee(subscription))
      }
    }
  }

  def getSubscriptions: Seq[Subscription] = {
    (for {
      (topic, groups) <- topicToSubscriptions
      (group, routees) <- groups
      routee <- routees
    } yield Subscription(topic, group, routee.ref)).toSeq
  }

  def insertSubscription(topic: String, group: String, subscription: ActorRef) {
    if (!subscriptions(subscription)) {
      context watch subscription
      subscriptions += subscription
    }
    topicToSubscriptions += topic -> (topicToSubscriptions(topic) + (group -> (topicToSubscriptions(topic)(group) + ActorRefRoutee(subscription))))
  }

  def removeSubscription(topic: String, group: String, subscription: ActorRef) {
    topicToSubscriptions += topic -> (topicToSubscriptions(topic) + (group -> (topicToSubscriptions(topic)(group) - ActorRefRoutee(subscription))))
    if (!existSubscription(subscription)) {
      context unwatch subscription
      subscriptions -= subscription
    }
  }

  def removeSubscription(subscription: ActorRef) {
    context unwatch subscription
    subscriptions -= subscription
    topicToSubscriptions = for {
      (topic, groups) <- topicToSubscriptions
      (group, routees) <- groups
    } yield topic -> (groups + (group -> (routees - ActorRefRoutee(subscription))))
  }

  override def receive: Receive = initial

  def initial: Receive = {
    case MediatorRegistered(ref) =>
      if (ref == self) {
        unstashAll()
        context.become(ready)
      } else {
        ref ! GetSubscriptions
      }

    case GetSubscriptionsAck(list) =>
      list foreach {
        s => insertSubscription(s.topic, s.group, s.ref)
      }
      unstashAll()
      context.become(ready)

    case _ => stash()
  }

  def ready: Receive = {
    case x @ SubscribeGroup(topic, group, subscription) =>
      pubsubMediator ! Publish(InternalTopic, PublishSubscribeGroup(x, self))
      val subscriber = sender()
      insertSubscription(topic, group, subscription)
      subscriber ! SubscribeAck(Subscribe(topic, subscription))

    case x @ UnsubscribeGroup(topic, group, subscription) =>
      pubsubMediator ! Publish(InternalTopic, PublishUnsubscribeGroup(x, self))
      val subscriber = sender()
      val ack = UnsubscribeAck(Unsubscribe(topic, subscription))
      removeSubscription(topic, group, subscription)
      subscriber ! ack

    case pub: PublishSubscribeGroup =>
      if (pub.origin != self) {
        self ! pub.subscribe
      }

    case pub: PublishUnsubscribeGroup =>
      if (pub.origin != self) {
        self ! pub.unsubscribe
      }

    case Publish(topic: String, msg: Any) =>
      topicToSubscriptions.get(topic) foreach (_.values foreach {
        refs => router.withRoutees(refs toVector).route(msg, sender())
      })

    case GetSubscriptions                         => sender() ! GetSubscriptionsAck(getSubscriptions)

    case Terminated(ref)                          => removeSubscription(ref)

    case _: SubscribeAck | _: GetSubscriptionsAck =>

    case x                                        => log.info("unhandled : " + x)
  }

}

object DistributedBalancingPubSubCoordinator {

  def props(mediator: ActorRef) = Props(classOf[DistributedBalancingPubSubCoordinator], mediator)

  case class MediatorRegister(ref: ActorRef)

  case class MediatorRegistered(ref: ActorRef)

}

class DistributedBalancingPubSubCoordinator(mediator: ActorRef) extends Actor with Stash {
  import DistributedBalancingPubSubCoordinator._

  override def receive: Actor.Receive = {
    case MediatorRegister(ref) => ref ! MediatorRegistered(mediator)
    case _                     =>
  }
}
