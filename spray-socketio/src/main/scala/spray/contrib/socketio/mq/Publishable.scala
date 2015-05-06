package spray.contrib.socketio.mq

import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Terminated
import akka.contrib.pattern.DistributedPubSubMediator.{ Publish, Subscribe, SubscribeAck, Unsubscribe, UnsubscribeAck }
import akka.event.LoggingAdapter
import akka.pattern.ask
import akka.routing.ActorRefRoutee
import akka.routing.Router
import scala.concurrent.duration._

trait Publishable extends Actor {

  var subscribers = Set[ActorRef]() // ActorRef of subscriber
  var groupToSubscribers: Map[Option[String], Set[ActorRefRoutee]] = Map.empty.withDefaultValue(Set.empty)

  def log: LoggingAdapter
  def groupRouter: Router

  def topic = self.path.name

  def publishableBehavior: Receive = {
    case x @ Subscribe(_, group, subscriber) =>
      insertSubscription(group, subscriber)
      sender() ! SubscribeAck(x)
      log.info("{} successfully subscribed to topic(me) [{}] under group [{}]", subscriber, topic, group)

    case x @ Unsubscribe(_, group, subscriber) =>
      removeSubscription(group, subscriber)
      sender() ! UnsubscribeAck(x)
      log.info("{} successfully unsubscribed to topic(me) [{}] under group [{}]", subscriber, topic, group)

    case Publish(_, msg, _) => publish(msg)

    case Terminated(ref)    => removeSubscription(ref)
  }

  def publish(x: Any) {
    groupToSubscribers foreach {
      case (None, subscribers) => subscribers foreach (_.ref ! x)
      case (_, subscribers)    => groupRouter.withRoutees(subscribers.toVector).route(x, self)
    }
  }

  def existsSubscriber(subscriber: ActorRef) = {
    groupToSubscribers exists { case (group, subscribers) => subscribers.contains(ActorRefRoutee(subscriber)) }
  }

  def insertSubscription(group: Option[String], subscriber: ActorRef) {
    if (!subscribers.contains(subscriber)) {
      context watch subscriber
      subscribers += subscriber
    }
    groupToSubscribers = groupToSubscribers.updated(group, groupToSubscribers(group) + ActorRefRoutee(subscriber))
  }

  def removeSubscription(group: Option[String], subscriber: ActorRef) {
    if (!existsSubscriber(subscriber)) {
      context unwatch subscriber
      subscribers -= subscriber
    }
    groupToSubscribers = groupToSubscribers.updated(group, groupToSubscribers(group) - ActorRefRoutee(subscriber))
  }

  def removeSubscription(subscriber: ActorRef) {
    context unwatch subscriber
    subscribers -= subscriber
    groupToSubscribers = for {
      (group, subscribers) <- groupToSubscribers
    } yield (group -> (subscribers - ActorRefRoutee(subscriber)))
  }

}

