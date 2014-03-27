package spray.contrib.socketio

import akka.pattern.ask
import akka.actor.{ ActorLogging, Actor, ActorRef }
import scala.collection.immutable
import spray.contrib.socketio
import akka.contrib.pattern.{ ClusterClient, ClusterReceptionistExtension }
import akka.contrib.pattern.DistributedPubSubMediator.Subscribe
import akka.contrib.pattern.DistributedPubSubMediator.Publish
import akka.contrib.pattern.DistributedPubSubMediator.Unsubscribe
import akka.contrib.pattern.DistributedPubSubMediator.SubscribeAck
import akka.contrib.pattern.DistributedPubSubMediator.UnsubscribeAck
import akka.routing.ActorRefRoutee
import akka.routing.AddRoutee
import akka.routing.GetRoutees
import akka.routing.RemoveRoutee
import akka.routing.RoundRobinGroup

object ClusterNamespaceMediator {

  case class Subscribe(topic: String, group: String, ref: ActorRef)

  case class Unsubscribe(topic: String, group: String, ref: ActorRef)

}

/**
 * A cluster singleton actor running on ConnectionActive nodes to manage subscriptions
 *
 */
//TODO make this actor persistent
class ClusterNamespaceMediator extends Actor with ActorLogging {

  var topicToSubscriptions: Map[String, Map[String, ActorRef]] = Map.empty.withDefaultValue(Map.empty)

  ClusterReceptionistExtension(context.system).registerService(self)

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
    case x @ ClusterNamespaceMediator.Subscribe(topic, group, subscription) =>
      val subscriber = sender()
      val router = getOrElseInsertSubscription(topic, group, subscription)
      import context.dispatcher
      router ! AddRoutee(ActorRefRoutee(subscription))
      (router ? GetRoutees)(socketio.actorResolveTimeout) onSuccess {
        case _ => subscriber ! SubscribeAck(Subscribe(topic, subscription))
      }

    case x @ ClusterNamespaceMediator.Unsubscribe(topic, group, subscription) =>
      val subscriber = sender()
      val ack = UnsubscribeAck(Unsubscribe(topic, subscription))
      val opt = getSubscription(topic, group)
      if (opt.isDefined) {
        val router = opt.get
        router ! RemoveRoutee(ActorRefRoutee(subscription))
        import context.dispatcher
        (router ? GetRoutees)(socketio.actorResolveTimeout) onSuccess {
          case _ => subscriber ! ack
        }
      } else {
        subscriber ! ack
      }

    case Publish(topic: String, msg: Any) =>
      topicToSubscriptions.get(topic) foreach (_.values foreach (_ ! msg))

    case x => log.info("unhandled : " + x)
  }

  //override def receiveRecover: Receive = ???
}

/**
 * ClusterNamespaceMediatorProxy is running on the namespace nodes
 *
 * @param path [[ClusterNamespaceMediator]] singleton path
 * @param client [[ClusterClient]] to access SocketIO Cluster
 */
class ClusterNamespaceMediatorProxy(path: String, client: ActorRef) extends Actor with ActorLogging {
  override def receive: Actor.Receive = {
    case Subscribe(topic, ref) =>
      client forward ClusterClient.Send(path, ClusterNamespaceMediator.Subscribe(topic, context.system.name, ref), false)
    case Unsubscribe(topic, ref) =>
      client forward ClusterClient.Send(path, ClusterNamespaceMediator.Unsubscribe(topic, context.system.name, ref), false)
    case x: Publish => client forward ClusterClient.Send(path, x, false)
  }
}
