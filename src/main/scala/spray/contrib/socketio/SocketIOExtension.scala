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
import spray.contrib.socketio.SocketIOGuardian.Start
import spray.contrib.socketio.SocketIOGuardian.Started

object SocketIOExtension extends ExtensionId[SocketIOExtension] with ExtensionIdProvider {
  override def get(system: ActorSystem): SocketIOExtension = super.get(system)

  override def lookup(): ExtensionId[_ <: Extension] = SocketIOExtension

  override def createExtension(system: ExtendedActorSystem): SocketIOExtension = new SocketIOExtension(system)

  val shardName: String = "connectionActives"

  lazy val idExtractor: ShardRegion.IdExtractor = {
    case cmd: ConnectionActive.Command => (cmd.sessionId, cmd)
  }

  lazy val shardResolver: ShardRegion.ShardResolver = {
    case cmd: ConnectionActive.Command => (math.abs(cmd.sessionId.hashCode) % 100).toString
  }
}

class SocketIOExtension(system: ExtendedActorSystem) extends Extension {
  /**
   * INTERNAL API
   */
  private[socketio] object Settings {
    val config = system.settings.config.getConfig("spray.socketio")
    val isCluster: Boolean = config.getString("mode") == "cluster"
  }

  import Settings._

  private lazy val localMediator = system.actorOf(Props(classOf[LocalMediator]), name = "socketio-localmediator")
  private lazy val localResolver = system.actorOf(Props(classOf[LocalConnectionActiveResolver], localMediator), name = SocketIOExtension.shardName)

  /**
   * Need to start immediatly to accept broadcase etc.
   */
  val mediator = if (isCluster) DistributedPubSubExtension(system).mediator else localMediator

  if (isCluster) {
    ClusterSharding(system).start(
      typeName = SocketIOExtension.shardName,
      entryProps = Some(Props(classOf[ClusterConnectionActive], mediator)),
      idExtractor = SocketIOExtension.idExtractor,
      shardResolver = SocketIOExtension.shardResolver)
  }

  lazy val resolver = if (isCluster) ClusterSharding(system).shardRegion(SocketIOExtension.shardName) else localResolver

  private lazy val guardian = system.actorOf(Props[SocketIOGuardian], "socketio-guardian")
  private lazy val namespaces = new TrieMap[String, ActorRef]

  def startNamespace(endpoint: String) {
    val namespace = socketio.namespaceFor(endpoint)
    implicit val timeout = system.settings.CreationTimeout
    val startMsg = Start(namespace, Props(classOf[Namespace], endpoint, mediator))
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