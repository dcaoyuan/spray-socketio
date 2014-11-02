package spray.contrib.socketio.namespace

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Props
import akka.actor.Terminated
import akka.contrib.pattern.ClusterClient
import akka.contrib.pattern.ClusterReceptionistExtension
import akka.contrib.pattern.ClusterSharding
import akka.contrib.pattern.DistributedPubSubMediator.{ Publish, Subscribe, SubscribeAck, Unsubscribe, UnsubscribeAck, SendToAll }
import akka.contrib.pattern.ShardRegion
import akka.pattern.ask
import akka.routing.ActorRefRoutee
import akka.routing.ConsistentHashingRouter.ConsistentHashable
import akka.routing.Router
import akka.routing.RoutingLogic
import scala.concurrent.duration._
import spray.contrib.socketio
import spray.contrib.socketio.SocketIOExtension

/**
 *
 *
 *    +===serverConn===+               +===connSession===+              +====namespace===+
 *    |                |    OnFrame    |                 |   OnPacket   |                |
 *    |                | ------------> |                 | -----------> |                |
 *    |                | <------------ |                 | <----------- |                |
 *    |                |  FrameCommand |                 |  SendPackets |                |
 *    +================+               +=================+              +================+
 *
 *
 *    +======node======+
 *    |       mediator----\
 *    |     /     |    |  |
 *    |    /      |    |  |
 *    | conn    conn   |  |
 *    | conn    conn   |  |---------------------------------------------------|
 *    |                |  |                  virtaul MEDIATOR                 |
 *    +================+  |---------------------------------------------------|
 *                        |                                                   |
 *                        |                                                   |
 *    +======node======+  |     +===========namesapce-node==============+     |
 *    |       mediator----/     | +endpointA (namespace) ----- mediator-------/
 *    |     /     |    |        |   |  |   |                            |
 *    |    /      |    |        |   |  |   +roomA                       |
 *    | conn    conn   |        |   |  |      |                         |
 *    | conn    conn   |        |   |  |      |                         |
 *    | /              |        |   |  |      \---> queueA              |
 *    +=|==============+        |   |  |      \---> queueB              |
 *      |                       |   |  |                                |
 *      \                       |   |  +roomB                           |
 *       \                      |   |     |                             |
 *    +---|-------------+       |   |     |                             |
 *    |   | region      |       |   |     \---> queueA --> [observer]-------\
 *    +---|-------------+       |   |     \---> queueB                  |   |
 *        |                     |   |                                   |   |
 *        |                     |   \---> queueA                        |   |
 *        |                     |   \---> queueB                        |   |
 *        |                     +=======================================+   |
 *        |                                                                 |
 *        |                                                                 |
 *        \-----------------------------------------------------------------/
 *
 *
 * @Note Akka can do millions of messages per second per actor per core.
 *
 * Namespace is sharding actor in socketio cluster, but, socketio's tranport/session nodes
 * are not aware of Namespace actors, because all messages in that cluster are sent to mediator.
 *
 * Namespace actors just accept messages via mediator, and then deliver them to
 * subscribted queues.
 */
object Namespace {

  def props(groupRoutingLogic: RoutingLogic) = Props(classOf[Namespace], groupRoutingLogic)

  val NamespaceEventSource = "socketio-namespace-event-source"

  sealed trait Command extends ConsistentHashable with Serializable {
    override def consistentHashKey = topic
    def topic: String
  }

  sealed trait Event extends ConsistentHashable with Serializable {
    override def consistentHashKey = topic
    def topic: String
  }

  case object AskTopic
  case object AskTopics extends Command {
    def topic = NamespaceEventSource
  }

  final case class Topic(topic: String)
  final case class Topics(topics: List[String])

  case class TopicCreated(topic: String, createdTopic: String) extends Event

  val shardName: String = "Namespaces"

  val idExtractor: ShardRegion.IdExtractor = {
    case x: Subscribe      => (x.topic, x)
    case x: Unsubscribe    => (x.topic, x)
    case x: SubscribeAck   => (x.subscribe.topic, x)
    case x: UnsubscribeAck => (x.unsubscribe.topic, x)
    case x: Publish        => (x.topic, x)
    case x: Event          => (x.topic, x)
  }

  val shardResolver: ShardRegion.ShardResolver = {
    case x: Subscribe      => hashForShard(x.topic)
    case x: Unsubscribe    => hashForShard(x.topic)
    case x: SubscribeAck   => hashForShard(x.subscribe.topic)
    case x: UnsubscribeAck => hashForShard(x.unsubscribe.topic)
    case x: Publish        => hashForShard(x.topic)
    case x: Event          => hashForShard(x.topic)
  }

  private def hashForShard(topic: String) = (math.abs(topic.hashCode) % 100).toString

  /**
   * It is recommended to load the ClusterReceptionistExtension when the actor
   * system is started by defining it in the akka.extensions configuration property:
   *   akka.extensions = ["akka.contrib.pattern.ClusterReceptionistExtension"]
   */
  def startSharding(system: ActorSystem, entryProps: Option[Props]) {
    val sharding = ClusterSharding(system)
    sharding.start(
      entryProps = entryProps,
      typeName = shardName,
      idExtractor = idExtractor,
      shardResolver = shardResolver)
    if (entryProps.isDefined) ClusterReceptionistExtension(system).registerService(sharding.shardRegion(shardName))
  }

  def shardRegion(system: ActorSystem) = ClusterSharding(system).shardRegion(shardName)

  final class SystemSingletons(system: ActorSystem) {
    lazy val clusterClient = {
      startSharding(system, None)
      val shardingGuardianName = system.settings.config.getString("akka.contrib.cluster.sharding.guardian-name")
      val path = shardPath(system)
      val originalClusterClient = SocketIOExtension(system).clusterClient
      system.actorOf(Props(classOf[ClusterClientBroker], path, originalClusterClient))
    }
  }

  def shardPath(system: ActorSystem) = {
    val shardingGuardianName = system.settings.config.getString("akka.contrib.cluster.sharding.guardian-name")
    s"/user/${shardingGuardianName}/${shardName}"
  }

  private var singletons: SystemSingletons = _
  private val singletonsMutex = new AnyRef()
  /**
   * Get the SystemSingletons, create it if none existed.
   *
   * @Note only one will be created no matter how many ActorSystems, actually
   * one ActorSystem per application usaully.
   */
  def apply(system: ActorSystem): SystemSingletons = {
    if (singletons eq null) {
      singletonsMutex synchronized {
        if (singletons eq null) {
          singletons = new SystemSingletons(system)
        }
      }
    }
    singletons
  }

  /**
   * A broker actor that runs on the business nodes to make forwarding msg to Namespace easily.
   *
   * @param path Namespace sharding service's path
   * @param originalClient [[ClusterClient]] to access SocketIO Cluster
   */
  class ClusterClientBroker(shardingServicePath: String, originalClient: ActorRef) extends Actor with ActorLogging {
    def receive = {
      case x: Subscribe      => originalClient forward ClusterClient.Send(shardingServicePath, x, false)
      case x: Unsubscribe    => originalClient forward ClusterClient.Send(shardingServicePath, x, false)
      case x: SubscribeAck   => originalClient forward ClusterClient.Send(shardingServicePath, x, false)
      case x: UnsubscribeAck => originalClient forward ClusterClient.Send(shardingServicePath, x, false)
    }
  }
}

/**
 * Namespace is refered to endpoint for observers and will subscribe to topic 'namespace' of mediator
 */
class Namespace(groupRoutingLogic: RoutingLogic) extends Actor with ActorLogging {
  import Namespace._

  val groupRouter = Router(groupRoutingLogic)

  var queues = Set[ActorRef]() // ActorRef of queue 
  var groupToQueues: Map[Option[String], Set[ActorRefRoutee]] = Map.empty.withDefaultValue(Set.empty)

  noticeTopicCreated()

  private def topic = self.path.name
  private def region = SocketIOExtension(context.system).namespaceRegion

  private def noticeTopicCreated() {
    topic match {
      case NamespaceEventSource =>
      case x                    => region ! TopicCreated(NamespaceEventSource, x)
    }
  }

  def receive: Receive = {
    case x @ Subscribe(topic, group, queue) =>
      val topic1 = topic match {
        case socketio.EmptyTopic => ""
        case x                   => x
      }

      insertSubscription(group, queue)
      sender() ! SubscribeAck(x)
      log.info("{} successfully subscribed to topic [{}] under group [{}]", queue, topic, group)

    case x @ Unsubscribe(topic, group, queue) =>
      val topic1 = topic match {
        case socketio.EmptyTopic => ""
        case x                   => x
      }

      removeSubscription(group, queue)
      sender() ! UnsubscribeAck(x)
      log.info("{} successfully unsubscribed to topic [{}] under group [{}]", queue, topic, group)

    case Publish(topic, msg, _) => deliverMessage(msg)

    case x: TopicCreated        => deliverMessage(x)

    case AskTopic =>
      sender() ! Topic(topic)
    //case AskTopics =>
    //mediator ! SendToAll(shardPath(context.system), AskTopic)
    //context.actorOf(TopicsAggregatorOnPull.props(sender(), 5.seconds))

    case Terminated(ref) => removeSubscription(ref)
  }

  def deliverMessage(x: Any) {
    groupToQueues foreach {
      case (None, queues) => queues foreach (_.ref ! x)
      case (_, queues)    => groupRouter.withRoutees(queues.toVector).route(x, self)
    }
  }

  def existsQueue(queue: ActorRef) = {
    groupToQueues exists { case (group, queues) => queues.contains(ActorRefRoutee(queue)) }
  }

  def insertSubscription(group: Option[String], queue: ActorRef) {
    if (!queues.contains(queue)) {
      context watch queue
      queues += queue
    }
    groupToQueues = groupToQueues.updated(group, groupToQueues(group) + ActorRefRoutee(queue))
  }

  def removeSubscription(group: Option[String], queue: ActorRef) {
    if (!existsQueue(queue)) {
      context unwatch queue
      queues -= queue
    }
    groupToQueues = groupToQueues.updated(group, groupToQueues(group) - ActorRefRoutee(queue))
  }

  def removeSubscription(queue: ActorRef) {
    context unwatch queue
    queues -= queue
    groupToQueues = for {
      (group, queues) <- groupToQueues
    } yield (group -> (queues - ActorRefRoutee(queue)))
  }

}

object TopicsAggregatorOnPull {
  def props(originalSender: ActorRef, duration: FiniteDuration) = Props(classOf[TopicsAggregatorOnPull], originalSender, duration)

  case object TimedOut
}

class TopicsAggregatorOnPull(originalSender: ActorRef, duration: FiniteDuration) extends Actor with ActorLogging {
  import TopicsAggregatorOnPull._

  var topics = Set[String]()

  import context.dispatcher
  context.system.scheduler.scheduleOnce(duration, self, TimedOut)

  def receive = {
    case Namespace.Topic(topic) =>
      topics += topic

    case TimedOut =>
      originalSender ! Namespace.Topics(topics.toList)
      context.stop(self)
  }
}
