package spray.contrib.socketio.extension

import java.util.concurrent.ConcurrentHashMap
import akka.actor._
import akka.pattern.ask
import spray.contrib.socketio.namespace.Namespace
import akka.contrib.pattern._
import spray.contrib.socketio.{ClusterConnectionActive, LocalConnectionActiveResolver, ConnectionActive, LocalMediator}
import scala.concurrent.Await
import scala.Some
import spray.contrib.socketio.extension.SocketIONamespaceGuardian.Start
import spray.contrib.socketio.extension.SocketIONamespaceGuardian.Started
import spray.contrib.socketio

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

  private val namespaces: ConcurrentHashMap[String, ActorRef] = new ConcurrentHashMap

  private lazy val guardian = system.actorOf(Props[SocketIONamespaceGuardian], "socket-io-guardian")

  if (isCluster) {
    ClusterSharding(system).start(
      typeName = ConnectionActive.shardName,
      entryProps = Some(Props(classOf[ClusterConnectionActive])),
      idExtractor = ClusterConnectionActive.idExtractor,
      shardResolver = ClusterConnectionActive.shardResolver)
  }

  val mediator = if (isCluster) DistributedPubSubExtension(system).mediator else LocalMediator(system)

  lazy val resolver = if (isCluster) ClusterSharding(system).shardRegion(ConnectionActive.shardName) else LocalConnectionActiveResolver(system)

  def startNamespace(endpoint: String = ""): ActorRef = {
    val namespace = socketio.namespaceFor(endpoint)
    implicit val timeout = system.settings.CreationTimeout
    val startMsg = Start(namespace, Props(classOf[Namespace], endpoint, mediator))
    val Started(namespaceRef) = Await.result(guardian ? startMsg, timeout.duration)
    namespaces.put(endpoint, namespaceRef)
    namespaceRef
  }

  def namespace(endpoint: String = ""): ActorRef = namespaces.get(endpoint) match {
    case null ⇒ throw new IllegalArgumentException(s"Namespace endpoint [$endpoint] must be started first")
    case ref: ActorRef  ⇒ ref
  }
}

private[socketio] object SocketIONamespaceGuardian {

  case class Start(endpoint: String, entryProps: Props) extends NoSerializationVerificationNeeded

  case class Started(namespace: ActorRef) extends NoSerializationVerificationNeeded

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