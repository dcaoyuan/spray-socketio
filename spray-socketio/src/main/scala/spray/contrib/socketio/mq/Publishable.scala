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

  var flows = Set[ActorRef]() // ActorRef of Flows 
  var groupToFlows: Map[Option[String], Set[ActorRefRoutee]] = Map.empty.withDefaultValue(Set.empty)

  def log: LoggingAdapter
  def groupRouter: Router

  def topic = self.path.name

  def publishableBehavior: Receive = {
    case x @ Subscribe(_, group, flow) =>
      insertSubscription(group, flow)
      sender() ! SubscribeAck(x)
      log.info("{} successfully subscribed to topic(me) [{}] under group [{}]", flow, topic, group)

    case x @ Unsubscribe(_, group, flow) =>
      removeSubscription(group, flow)
      sender() ! UnsubscribeAck(x)
      log.info("{} successfully unsubscribed to topic(me) [{}] under group [{}]", flow, topic, group)

    case Publish(_, msg, _) => publish(msg)

    case Terminated(ref)    => removeSubscription(ref)
  }

  def publish(x: Any) {
    groupToFlows foreach {
      case (None, flows) => flows foreach (_.ref ! x)
      case (_, flows)    => groupRouter.withRoutees(flows.toVector).route(x, self)
    }
  }

  def existsFlow(flow: ActorRef) = {
    groupToFlows exists { case (group, flows) => flows.contains(ActorRefRoutee(flow)) }
  }

  def insertSubscription(group: Option[String], flow: ActorRef) {
    if (!flows.contains(flow)) {
      context watch flow
      flows += flow
    }
    groupToFlows = groupToFlows.updated(group, groupToFlows(group) + ActorRefRoutee(flow))
  }

  def removeSubscription(group: Option[String], flow: ActorRef) {
    if (!existsFlow(flow)) {
      context unwatch flow
      flows -= flow
    }
    groupToFlows = groupToFlows.updated(group, groupToFlows(group) - ActorRefRoutee(flow))
  }

  def removeSubscription(flow: ActorRef) {
    context unwatch flow
    flows -= flow
    groupToFlows = for {
      (group, flows) <- groupToFlows
    } yield (group -> (flows - ActorRefRoutee(flow)))
  }

}

