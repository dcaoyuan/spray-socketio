package spray.contrib.socketio.mq

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.ExtendedActorSystem
import akka.actor.Extension
import akka.actor.ExtensionId
import akka.actor.ExtensionIdProvider
import akka.actor.Props
import akka.contrib.pattern.ClusterClient
import akka.contrib.pattern.ClusterReceptionistExtension
import akka.contrib.pattern.ClusterSharding
import akka.contrib.pattern.ClusterSingletonProxy
import akka.contrib.pattern.DistributedPubSubMediator.{ Publish, Subscribe, SubscribeAck, Unsubscribe, UnsubscribeAck }
import akka.contrib.pattern.ShardRegion
import akka.pattern.ask
import akka.routing.Router
import akka.routing.RoutingLogic
import scala.concurrent.duration._
import spray.contrib.socketio
import spray.contrib.socketio.mq.Aggregator.ReportingData

/**
 *
 *
 *    +===serverConn===+               +===connSession===+              +======topic=====+
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
 *    +======node======+  |     +===============mq-node=================+     |
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
 * Topic is sharding actor in socketio cluster, but, socketio's tranport/session nodes
 * are not aware of Topic actors, because all messages in that cluster are sent to mediator.
 *
 * Topic actors just accept messages via mediator, and then deliver them to
 * subscribted queues.
 */
object Topic extends ExtensionId[TopicExtension] with ExtensionIdProvider {
  // -- implementation of akka extention 
  override def get(system: ActorSystem) = super.get(system)
  override def lookup = Topic
  override def createExtension(system: ExtendedActorSystem) = new TopicExtension(system)
  // -- end of implementation of akka extention 

  def props(groupRoutingLogic: RoutingLogic) = Props(classOf[Topic], groupRoutingLogic)

  /**
   * topic cannot be "", which will be sent via DistributedPubSubMediator -- Singleton Proxy or Cluster Client
   */
  val EMPTY = "global-topic-empty"

  private case object ReportingTick

  val shardName: String = "Topics"

  val idExtractor: ShardRegion.IdExtractor = {
    case x: Subscribe      => (x.topic, x)
    case x: Unsubscribe    => (x.topic, x)
    case x: SubscribeAck   => (x.subscribe.topic, x)
    case x: UnsubscribeAck => (x.unsubscribe.topic, x)
    case x: Publish        => (x.topic, x)
  }

  val shardResolver: ShardRegion.ShardResolver = {
    case x: Subscribe      => hashForShard(x.topic)
    case x: Unsubscribe    => hashForShard(x.topic)
    case x: SubscribeAck   => hashForShard(x.subscribe.topic)
    case x: UnsubscribeAck => hashForShard(x.unsubscribe.topic)
    case x: Publish        => hashForShard(x.topic)
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
  def shardRegionPath(system: ActorSystem) = {
    val shardingGuardianName = system.settings.config.getString("akka.contrib.cluster.sharding.guardian-name")
    s"/user/${shardingGuardianName}/${shardName}"
  }

  val TopicAggregator = "aggregator-topic"
  val TopicAggregatorPath = "/user/" + Aggregator.singletonManagerNameForAggregate(TopicAggregator) + "/" + TopicAggregator
  val TopicAggregatorProxyName = "topicAggregatorProxy"
  val TopicAggregatorProxyPath = "/user/" + TopicAggregatorProxyName

  /**
   * Start on all nodes which has Some(role) within cluster
   */
  def startTopicAggregator(system: ActorSystem, role: Option[String]) {
    Aggregator.startAggregator(system, TopicAggregator, role = role)
  }

  /**
   * Start on nodes within cluster only
   */
  def startTopicAggregatorProxy(system: ActorSystem, role: Option[String]) {
    val proxy = system.actorOf(
      ClusterSingletonProxy.props(
        singletonPath = Topic.TopicAggregatorPath,
        role = role),
      name = Topic.TopicAggregatorProxyName)
    ClusterReceptionistExtension(system).registerService(proxy)
  }

  /**
   * A broker actor that runs outside of the cluster to forward msg to sharding actor easily.
   *
   * @param path sharding service's path
   * @param originalClient [[ClusterClient]] to access Cluster
   */
  class ClusterClientBroker(servicePath: String, originalClient: ActorRef) extends Actor with ActorLogging {
    def receive = {
      case x => originalClient forward ClusterClient.Send(servicePath, x, false)
    }
  }

}

/**
 * Topic is refered to endpoint for observers
 */
class Topic(groupRoutingLogic: RoutingLogic) extends Publishable with Actor with ActorLogging {
  import Topic._
  import context.dispatcher

  val groupRouter = Router(groupRoutingLogic)

  def isAggregator = false
  private def topicAggregator = Topic(context.system).topicAggregatorProxy

  val reportingTask = if (isAggregator) {
    None
  } else {
    topicAggregator ! ReportingData(topic)
    val settings = new Aggregator.Settings(context.system)
    Some(context.system.scheduler.schedule(settings.AggregatorReportingInterval, settings.AggregatorReportingInterval, self, ReportingTick))
  }

  override def postStop(): Unit = {
    super.postStop()
    reportingTask foreach { _.cancel }
  }

  def receive: Receive = publishableBehavior orElse reportingTickiBehavior

  def reportingTickiBehavior: Receive = {
    case ReportingTick => topicAggregator ! ReportingData(topic)
  }
}

class TopicExtension(system: ExtendedActorSystem) extends Extension {

  lazy val originalClusterClient = {
    import scala.collection.JavaConversions._
    val initialContacts = system.settings.config.getStringList("spray.socketio.cluster.client-initial-contacts").toSet
    system.actorOf(ClusterClient.props(initialContacts map system.actorSelection), "socketio-topic-cluster-client")
  }

  lazy val clusterClient = {
    val path = Topic.shardRegionPath(system)
    system.actorOf(Props(classOf[Topic.ClusterClientBroker], path, originalClusterClient))
  }

  lazy val topicAggregatorProxy = system.actorSelection(Topic.TopicAggregatorProxyPath)

  lazy val topicAggregatorClient = {
    system.actorOf(Props(classOf[Topic.ClusterClientBroker], Topic.TopicAggregatorProxyPath, originalClusterClient))
  }

}