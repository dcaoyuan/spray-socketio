package spray.contrib.socketio

import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.ExtendedActorSystem
import akka.actor.Extension
import akka.actor.ExtensionId
import akka.actor.ExtensionIdProvider
import akka.actor.NoSerializationVerificationNeeded
import akka.actor.Props
import akka.contrib.pattern._
import akka.pattern.ask
import scala.collection.concurrent.TrieMap
import scala.concurrent.Await
import spray.contrib.socketio
import spray.contrib.socketio.namespace.Namespace
import spray.contrib.socketio.SocketIONamespaceGuardian.Start
import spray.contrib.socketio.SocketIONamespaceGuardian.Started

object SocketIOExtension extends ExtensionId[SocketIOExtension] with ExtensionIdProvider {
  override def get(system: ActorSystem): SocketIOExtension = super.get(system)

  override def lookup(): ExtensionId[_ <: Extension] = SocketIOExtension

  override def createExtension(system: ExtendedActorSystem): SocketIOExtension = new SocketIOExtension(system)
}

class SocketIOExtension(system: ExtendedActorSystem) extends Extension {
  /**
   * INTERNAL API
   */
  private[socketio] object Settings {
    val config = system.settings.config.getConfig("spray.socketio")
    val isCluster: Boolean = config.hasPath("mode") && config.getString("mode") == "cluster"
  }

  import Settings._

  private val namespaces = new TrieMap[String, ActorRef]

  private lazy val guardian = system.actorOf(Props[SocketIONamespaceGuardian], "socketio-guardian")

  if (isCluster) {
    ClusterSharding(system).start(
      typeName = ConnectionActive.shardName,
      entryProps = Some(Props(classOf[ClusterConnectionActive])),
      idExtractor = ClusterConnectionActive.idExtractor,
      shardResolver = ClusterConnectionActive.shardResolver)
  }

  val mediator = if (isCluster) DistributedPubSubExtension(system).mediator else LocalMediator(system)

  lazy val resolver = if (isCluster) ClusterSharding(system).shardRegion(ConnectionActive.shardName) else LocalConnectionActiveResolver(system)

  def startNamespace(endpoint: String): ActorRef = {
    val namespace = socketio.namespaceFor(endpoint)
    implicit val timeout = system.settings.CreationTimeout
    val startMsg = Start(namespace, Props(classOf[Namespace], endpoint, mediator))
    val Started(namespaceRef) = Await.result(guardian ? startMsg, timeout.duration)
    namespaces(endpoint) = namespaceRef
    namespaceRef
  }

  def namespace(endpoint: String): ActorRef = namespaces.get(endpoint) match {
    case None      => throw new IllegalArgumentException(s"Namespace endpoint [$endpoint] must be started first")
    case Some(ref) => ref
  }
}

private[socketio] object SocketIONamespaceGuardian {
  final case class Start(endpoint: String, entryProps: Props) extends NoSerializationVerificationNeeded
  final case class Started(namespace: ActorRef) extends NoSerializationVerificationNeeded
}

private[socketio] class SocketIONamespaceGuardian extends Actor {
  override def receive: Actor.Receive = {
    case Start(namespace, entryProps) =>
      val namespaceRef: ActorRef = context.child(namespace).getOrElse {
        context.actorOf(entryProps, name = namespace)
      }
      sender() ! Started(namespaceRef)
  }
}