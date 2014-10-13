package spray.contrib.socketio.namespace

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.ExtendedActorSystem
import akka.actor.Extension
import akka.actor.ExtensionId
import akka.actor.ExtensionIdProvider
import akka.actor.NoSerializationVerificationNeeded
import akka.actor.Props
import akka.contrib.pattern.ClusterClient
import akka.contrib.pattern.DistributedPubSubMediator.Publish
import akka.contrib.pattern.DistributedPubSubMediator.Subscribe
import akka.contrib.pattern.DistributedPubSubMediator.Unsubscribe
import akka.pattern.ask
import scala.collection.concurrent.TrieMap
import scala.collection.immutable
import scala.concurrent.Await
import spray.contrib.socketio
import spray.contrib.socketio.SocketIOExtension

object NamespaceExtension extends ExtensionId[NamespaceExtension] with ExtensionIdProvider {
  override def get(system: ActorSystem): NamespaceExtension = super.get(system)

  override def lookup(): ExtensionId[_ <: Extension] = NamespaceExtension

  override def createExtension(system: ExtendedActorSystem): NamespaceExtension = new NamespaceExtension(system)
}

class NamespaceExtension(system: ExtendedActorSystem) extends Extension {
  private lazy val namespaces = new TrieMap[String, ActorRef]

  private lazy val guardian = system.actorOf(NamespaceGuardian.props, "socketio-guardian")

  def startNamespace(endpoint: String) {
    implicit val timeout = system.settings.CreationTimeout
    val name = socketio.topicForNamespace(endpoint)
    val mediator = SocketIOExtension(system).mediatorProxy
    val startMsg = NamespaceGuardian.Start(name, Namespace.props(mediator))
    val NamespaceGuardian.Started(namespaceRef) = Await.result(guardian ? startMsg, timeout.duration)
    namespaces(endpoint) = namespaceRef
  }

  def namespace(endpoint: String) = namespaces.getOrElse(endpoint, {
    throw new IllegalArgumentException(s"Namespace endpoint [$endpoint] must be started first")
  })
}

private[socketio] object NamespaceGuardian {
  def props() = Props(classOf[NamespaceGuardian])

  final case class Start(name: String, entryProps: Props) extends NoSerializationVerificationNeeded
  final case class Started(ref: ActorRef) extends NoSerializationVerificationNeeded
}

private[socketio] class NamespaceGuardian extends Actor {
  import NamespaceGuardian._
  def receive: Actor.Receive = {
    case Start(name, entryProps) =>
      val ref = context.child(name).getOrElse {
        context.actorOf(entryProps, name = name)
      }
      sender() ! Started(ref)
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
 * @param path [[spray.contrib.socketio.DistributedBalancingPubSubMediator]] service path
 * @param group consumer group of the topics
 * @param client [[ClusterClient]] to access Cluster
 */
class DistributedBalancingPubSubProxy(path: String, group: String, client: ActorRef) extends Actor with ActorLogging {
  def receive: Actor.Receive = {
    case Subscribe(topic, None, ref) =>
      client forward ClusterClient.Send(path, socketio.DistributedBalancingPubSubMediator.SubscribeGroup(topic, group, ref), false)
    case Unsubscribe(topic, None, ref) =>
      client forward ClusterClient.Send(path, socketio.DistributedBalancingPubSubMediator.UnsubscribeGroup(topic, group, ref), false)
    case x: Publish =>
      client forward ClusterClient.Send(path, x, false)
  }
}
