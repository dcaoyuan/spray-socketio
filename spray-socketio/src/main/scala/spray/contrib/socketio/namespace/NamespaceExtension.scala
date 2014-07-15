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
import akka.pattern.ask
import akka.contrib.pattern.DistributedPubSubMediator.Publish
import akka.contrib.pattern.DistributedPubSubMediator.Subscribe
import akka.contrib.pattern.DistributedPubSubMediator.Unsubscribe
import scala.collection.concurrent.TrieMap
import scala.collection.immutable
import scala.concurrent.Await
import spray.contrib.socketio
import spray.contrib.socketio.ConnectionActive
import spray.contrib.socketio.SocketIOExtension

object NamespaceExtension extends ExtensionId[NamespaceExtension] with ExtensionIdProvider {
  override def get(system: ActorSystem): NamespaceExtension = super.get(system)

  override def lookup(): ExtensionId[_ <: Extension] = NamespaceExtension

  override def createExtension(system: ExtendedActorSystem): NamespaceExtension = new NamespaceExtension(system)
}

class NamespaceExtension(system: ExtendedActorSystem) extends Extension {
  /**
   * INTERNAL API
   */
  private[socketio] object Settings {
    val config = system.settings.config.getConfig("spray.socketio")
    val isCluster: Boolean = config.getString("mode") == "cluster"
    val namespaceGroup = config.getString("server.namespace-group-name")
  }

  import Settings._

  private lazy val namespaces = new TrieMap[String, ActorRef]

  private lazy val guardian = system.actorOf(NamesapceGuardian.props, "socketio-guardian")

  private lazy val client = if (isCluster) {
    ConnectionActive(system).clusterClient
  } else {
    ActorRef.noSender
  }

  val mediator = if (isCluster) {
    system.actorOf(DistributedBalancingPubSubProxy.props(s"/user/${SocketIOExtension.mediatorName}", namespaceGroup, client))
  } else {
    SocketIOExtension(system).localMediator
  }

  lazy val resolver = if (isCluster) {
    socketio.ConnectionActiveClusterClient(system)
  } else {
    SocketIOExtension(system).resolver
  }

  def startNamespace(endpoint: String) {
    implicit val timeout = system.settings.CreationTimeout
    val name = socketio.topicForNamespace(endpoint)
    val startMsg = NamesapceGuardian.Start(name, Namespace.props(endpoint, mediator))
    val NamesapceGuardian.Started(namespaceRef) = Await.result(guardian ? startMsg, timeout.duration)
    namespaces(endpoint) = namespaceRef
  }

  def namespace(endpoint: String): ActorRef = namespaces.get(endpoint) match {
    case Some(ref) => ref
    case None      => throw new IllegalArgumentException(s"Namespace endpoint [$endpoint] must be started first")
  }
}

private[socketio] object NamesapceGuardian {
  def props() = Props(classOf[NamesapceGuardian])

  final case class Start(name: String, entryProps: Props) extends NoSerializationVerificationNeeded
  final case class Started(ref: ActorRef) extends NoSerializationVerificationNeeded
}

private[socketio] class NamesapceGuardian extends Actor {
  import NamesapceGuardian._
  def receive: Actor.Receive = {
    case Start(name, entryProps) =>
      val ref: ActorRef = context.child(name).getOrElse {
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
  override def receive: Actor.Receive = {
    case Subscribe(topic, None, ref) =>
      client forward ClusterClient.Send(path, socketio.DistributedBalancingPubSubMediator.SubscribeGroup(topic, group, ref), false)
    case Unsubscribe(topic, None, ref) =>
      client forward ClusterClient.Send(path, socketio.DistributedBalancingPubSubMediator.UnsubscribeGroup(topic, group, ref), false)
    case x: Publish =>
      client forward ClusterClient.Send(path, x, false)
  }
}
