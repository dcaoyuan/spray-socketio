package spray.contrib.socketio

import akka.actor._
import akka.pattern.ask
import akka.japi.Util.immutableSeq
import scala.collection.concurrent.TrieMap
import spray.contrib.socketio.namespace.Namespace
import scala.concurrent.Await
import spray.contrib.socketio.SocketIOGuardian.{ Start, Started }
import akka.contrib.pattern.ClusterClient
import scala.collection.immutable
import spray.contrib.socketio.SocketIOGuardian.Started
import spray.contrib.socketio.SocketIOGuardian.Start
import scala.Some

object SocketIONamespaceExtension extends ExtensionId[SocketIONamespaceExtension] with ExtensionIdProvider {
  override def get(system: ActorSystem): SocketIONamespaceExtension = super.get(system)

  override def lookup(): ExtensionId[_ <: Extension] = SocketIONamespaceExtension

  override def createExtension(system: ExtendedActorSystem): SocketIONamespaceExtension = new SocketIONamespaceExtension(system)
}

class SocketIONamespaceExtension(system: ExtendedActorSystem) extends Extension {
  /**
   * INTERNAL API
   */
  private[socketio] object Settings {
    val shardingName = system.settings.config.getString("akka.contrib.cluster.sharding.guardian-name")
    val config = system.settings.config.getConfig("spray.socketio")
    val isCluster: Boolean = config.getString("mode") == "cluster"
    println("list:" + config.getList("seed-nodes"))
    val SeedNodes: Seq[String] = immutableSeq(config.getStringList("seed-nodes"))
  }

  import Settings._

  private lazy val namespaces = new TrieMap[String, ActorRef]

  private lazy val guardian = system.actorOf(Props[SocketIOGuardian], "socketio-guardian")

  private lazy val client = if (isCluster) {
    system.actorOf(ClusterClient.props(SeedNodes map (system.actorSelection) toSet), "socketio-cluster-client")
  } else ActorRef.noSender

  val mediator = if (isCluster) system.actorOf(Props(classOf[ClusterNamespaceMediatorProxy], s"/user/${SocketIOExtension.mediatorName}/${SocketIOExtension.mediatorSingleton}", client)) else SocketIOExtension(system).namespaceMediator

  lazy val resolver = if (isCluster) system.actorOf(Props(classOf[ClusterConnectionActiveResolverProxy], s"/user/${shardingName}/${SocketIOExtension.shardName}", client)) else SocketIOExtension(system).resolver

  def startNamespace(endpoint: String) {
    implicit val timeout = system.settings.CreationTimeout
    val name = "socketio-namespace-" + { if (endpoint == "") "global" else endpoint }
    val startMsg = Start(name, Props(classOf[Namespace], endpoint, mediator))
    val Started(namespaceRef) = Await.result(guardian ? startMsg, timeout.duration)
    namespaces(endpoint) = namespaceRef
  }

  def namespace(endpoint: String): ActorRef = namespaces.get(endpoint) match {
    case None      => throw new IllegalArgumentException(s"Namespace endpoint [$endpoint] must be started first")
    case Some(ref) => ref
  }
}

private[socketio] object SocketIOGuardian {
  final case class Start(name: String, entryProps: Props) extends NoSerializationVerificationNeeded
  final case class Started(ref: ActorRef) extends NoSerializationVerificationNeeded
}

private[socketio] class SocketIOGuardian extends Actor {
  override def receive: Actor.Receive = {
    case Start(name, entryProps) =>
      val ref: ActorRef = context.child(name).getOrElse {
        context.actorOf(entryProps, name = name)
      }
      sender() ! Started(ref)
  }
}
