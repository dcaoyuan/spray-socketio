package spray.contrib.socketio

import akka.actor.ActorIdentity
import akka.actor.Identify
import akka.actor._
import akka.cluster.Cluster
import akka.cluster.ClusterEvent._
import akka.cluster.MemberStatus
import akka.contrib.pattern.ClusterSharding
import akka.contrib.pattern.DistributedPubSubExtension
import akka.contrib.pattern.DistributedPubSubMediator.{Count, Subscribe, Unsubscribe, SubscribeAck, UnsubscribeAck }
import akka.io.{Tcp, IO}
import akka.persistence.journal.leveldb.{ SharedLeveldbJournal, SharedLeveldbStore }
import akka.persistence.Persistence
import akka.pattern.ask
import akka.remote.testconductor.RoleName
import akka.remote.testkit.{ MultiNodeSpec, MultiNodeConfig }
import akka.stream.ActorFlowMaterializer
import akka.stream.actor.ActorSubscriber
import akka.stream.actor.ActorSubscriberMessage.OnNext
import akka.stream.actor.WatermarkRequestStrategy
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.testkit.ImplicitSender
import akka.testkit.TestProbe
import com.typesafe.config.ConfigFactory
import java.io.File
import org.iq80.leveldb.util.FileUtils
import scala.concurrent.Await
import scala.concurrent.Promise
import scala.concurrent.duration._
import spray.can.Http
import spray.can.websocket.frame.{TextFrame, Frame}
import spray.can.server.UHttp
import spray.contrib.socketio
import spray.contrib.socketio.ConnectionSession.OnPacket
import spray.contrib.socketio.ConnectionSession.OnEvent
import spray.contrib.socketio.SocketIOClusterSpec.SocketIOClient.OnOpen
import spray.contrib.socketio.SocketIOClusterSpec.SocketIOClient.SendHello
import spray.contrib.socketio.mq.Aggregator
import spray.contrib.socketio.mq.Queue
import spray.contrib.socketio.mq.Topic
import spray.contrib.socketio.packet.{EventPacket, Packet, MessagePacket}
import spray.json.{JsArray, JsString}

/**
 * Note:
 * 1. cluster singleton proxy or cluster sharding proxy does not need to contain the corresponding role. 
 *
 * But
 *
 * 2. Start sharding or its proxy will try to create sharding coordinate singleton on the oldest node, 
 *    so the oldest node has to contain those (singleton, sharding) corresponding roles and start these
 *    sharding/singleton entry or proxy. 
 * 3. If the sharding coordinate is not be created/located in cluster yet, the sharding proxy in other node
 *    could not identify the coordinate singleton, which means, if you want to a sharding proxy to work 
 *    properly and which has no corresponding role contained, you have to wait for the coordinate singleton
 *    is ready in cluster.
 *
 * The sharding's singleton coordinator will be created and located at the oldest node.

 * Anyway, to free the nodes starting order, the first started node (oldest) should start all sharding 
 * sevices (or proxy) and singleton manager (or proxy) and thus has to contain all those corresponding roles,
 */
object SocketIOClusterSpecConfig extends MultiNodeConfig {
  // first node is a special node for test spec
  val controller = role("controller") // JVM1

  val topic1     = role("topic1") // JVM2
  val topic2     = role("topic2") // JVM3
  val transport1 = role("transport1") // JVM4
  val transport2 = role("transport2") // JVM5
  val session1   = role("session1") // JVM6
  val session2   = role("session2") // JVM7
  val business1  = role("business1") // JVM8
  val business2  = role("business2") // JVM9
  val business3  = role("business3") // JVM10

  val client1 = role("client1") // JVM11
  val client2 = role("client2") // JVM12

  val host = "127.0.0.1"

  val port1 = 8081
  val port2 = 8082

  commonConfig(ConfigFactory.parseString(
    """
      akka.loglevel = INFO
      akka.actor.provider = "akka.cluster.ClusterActorRefProvider"
      akka.extensions = ["akka.contrib.pattern.ClusterReceptionistExtension"]
      akka.persistence.journal.plugin = "akka.persistence.journal.leveldb-shared"
      akka.persistence.journal.leveldb-shared.store {
        native = off
        dir = "target/test-shared-journal"
      }
      akka.persistence.snapshot-store.local.dir = "target/test-snapshots"
      spray.socketio.mode = "cluster"
      akka.cluster.seed-nodes = [
        "akka.tcp://SocketIOClusterSpec@localhost:2551",
        "akka.tcp://SocketIOClusterSpec@localhost:2552"
      ]
    """))

  nodeConfig(topic1) {
    ConfigFactory.parseString(
      """
        akka.remote.netty.tcp.port = 2551
        akka.cluster.roles = ["topic", "session"]
        akka.contrib.cluster.sharding.role = "topic"
      """)
  }

  nodeConfig(topic2) {
    ConfigFactory.parseString(
      """
        akka.cluster.roles = ["topic"]
        akka.contrib.cluster.sharding.role = "topic"
      """)
  }

  nodeConfig(session1) {
    ConfigFactory.parseString(
      """
        akka.remote.netty.tcp.port = 2552
        akka.cluster.roles = ["session", "topic"]
        akka.contrib.cluster.sharding.role = "session"
      """)
  }

  nodeConfig(session2) {
    ConfigFactory.parseString(
      """
        akka.cluster.roles = ["session", "topic"]
        akka.contrib.cluster.sharding.role = "session"
      """)
  }

  // We set topic and session node as the candicate of first starting node only,
  // so transport is not necessary to contain role "session" or "topic"
  nodeConfig(transport1, transport2) {
    ConfigFactory.parseString(
      """
        akka.cluster.roles =["transport"]
        akka.contrib.cluster.sharding.role = ""
      """)
  }

  nodeConfig(business1, business2, business3) {
    ConfigFactory.parseString(
      """
        akka.cluster.roles = ["business"]
        spray.socketio {
            cluster.client-initial-contacts = [
                "akka.tcp://SocketIOClusterSpec@localhost:2551/user/receptionist",
                "akka.tcp://SocketIOClusterSpec@localhost:2552/user/receptionist"
            ]
        }
      """)

  }

}

class SocketIOClusterSpecMultiJvmNode1 extends SocketIOClusterSpec
class SocketIOClusterSpecMultiJvmNode2 extends SocketIOClusterSpec
class SocketIOClusterSpecMultiJvmNode3 extends SocketIOClusterSpec
class SocketIOClusterSpecMultiJvmNode4 extends SocketIOClusterSpec
class SocketIOClusterSpecMultiJvmNode5 extends SocketIOClusterSpec
class SocketIOClusterSpecMultiJvmNode6 extends SocketIOClusterSpec
class SocketIOClusterSpecMultiJvmNode7 extends SocketIOClusterSpec
class SocketIOClusterSpecMultiJvmNode8 extends SocketIOClusterSpec
class SocketIOClusterSpecMultiJvmNode9 extends SocketIOClusterSpec
class SocketIOClusterSpecMultiJvmNode10 extends SocketIOClusterSpec
class SocketIOClusterSpecMultiJvmNode11 extends SocketIOClusterSpec
class SocketIOClusterSpecMultiJvmNode12 extends SocketIOClusterSpec


object SocketIOClusterSpec {

  class SocketIOServer(probe: ActorRef) extends Actor with ActorLogging {
    def sessionRegion = ConnectionSession.shardRegion(context.system)

    def receive = {
      case x: Tcp.Bound => probe ! x
        // when a new connection comes in we register a SocketIOConnection actor as the per connection handler

      case Http.Connected(remoteAddress, localAddress) =>
        val serverConnection = sender()
        val conn = context.actorOf(Props(classOf[SocketIOWorker], serverConnection))
        serverConnection ! Http.Register(conn)
    }

  }

  class SocketIOWorker(val serverConnection: ActorRef) extends Actor with SocketIOServerWorker {
    def sessionRegion = ConnectionSession.shardRegion(context.system)

    def genericLogic: Receive = {
      case x: Frame =>
    }
  }

  object SocketIOClient {
    case object OnOpen
    case object OnClose

    case object SendHello
    case class SendBroadcast(msg: String)
  }

  class SocketIOClient(connect: Http.Connect, probe: ActorRef) extends Actor with SocketIOClientWorker {
    import SocketIOClient._

    import context.system

    IO(UHttp) ! connect

    def businessLogic: Receive = {
      case SendHello           => connection ! TextFrame("5:::{\"name\":\"chat\", \"args\":[]}")
      case SendBroadcast(msg)  => connection ! TextFrame("""5:::{"name":"broadcast", "args":[""" + "\"" + msg + "\"" + "]}")
    }

    override def onDisconnected(endpoint: String) {
      probe ! OnClose
    }

    override def onOpen() {
      probe ! OnOpen
      log.info("onOpen. sending OnOpen to {}", probe)
    }

    def onPacket(packet: Packet) {
      log.info("onPacket: {}", packet)
      packet match {
        case EventPacket("chat", args) => probe ! SendHello
        case msg: MessagePacket => probe ! msg.data
        case _ =>
      }

    }
  }
  
  class MsgWorker(socketioExt: SocketIOExtension, probe: ActorRef) extends ActorSubscriber {
    override val requestStrategy = WatermarkRequestStrategy(10)
    def receive = {
      case OnNext(value @ OnEvent("chat", args, context)) =>
        value.replyEvent("chat", args)(socketioExt.sessionClient)
      case OnNext(value @ OnEvent("broadcast", args, context)) =>
        val msg = spray.json.JsonParser(args).asInstanceOf[JsArray].elements.head.asInstanceOf[JsString].value
        value.broadcast("", MessagePacket(-1, false, value.endpoint, msg))(socketioExt.sessionClient)
      case OnNext(value) =>
        println("observed: " + value)
    }
  }

  class TopicAggregatorWorker(probe: ActorRef) extends ActorSubscriber with ActorLogging {
    override val requestStrategy = WatermarkRequestStrategy(10)
    def receive = {
      case OnNext(value : Aggregator.Available) =>
        log.info("Got {}", value)
        probe ! value
      case OnNext(value : Aggregator.Unavailable) =>
        log.info("Got {}", value)
        probe ! value
       case OnNext(value) =>
        println("observed: " + value)
    }
  }
}

class SocketIOClusterSpec extends MultiNodeSpec(SocketIOClusterSpecConfig) with STMultiNodeSpec with ImplicitSender {

  import SocketIOClusterSpecConfig._
  import SocketIOClusterSpec._

  implicit val materializer = ActorFlowMaterializer()

  override def initialParticipants: Int = roles.size

  val storageLocations = List(
    "akka.persistence.journal.leveldb.dir",
    "akka.persistence.journal.leveldb-shared.store.dir",
    "akka.persistence.snapshot-store.local.dir").map(s => new File(system.settings.config.getString(s)))

  override protected def atStartup() {
    runOn(controller) {
      storageLocations.foreach(dir => FileUtils.deleteRecursively(dir))
    }
  }

  override protected def afterTermination() {
    runOn(controller) {
      storageLocations.foreach(dir => FileUtils.deleteRecursively(dir))
    }
  }

  "Sharded socketio cluster" must {

    "setup shared journal" in within(10.seconds) {
      // start the Persistence extension
      Persistence(system)
      runOn(controller) {
        system.actorOf(Props[SharedLeveldbStore], "store")
      }

      enterBarrier("peristence-started")

      runOn(topic1, topic2, session1, session2) {
        system.actorSelection(node(controller) / "user" / "store") ! Identify(None)
        val sharedStore = expectMsgType[ActorIdentity].ref.get
        SharedLeveldbJournal.setStore(sharedStore, system)
      }

      enterBarrier("setup-persistence")
    }

    "start cluster" in within(30.seconds) {

      val cluster = Cluster(system)

      // start seed node first, could be topic1 or session1
      // if you want to test either order, change the order of following code of
      //   runOn(topic1, topic2)
      //   runOn(session1, session2)

      // with roles: topic, session
      runOn(topic1, topic2) { 
        Topic.startTopicAggregator(system, role = Some("topic"))
        // should start the proxy too, since topics should report to topicAggregator via this proxy
        Topic.startTopicAggregatorProxy(system, role = Some("topic")) 
        Topic.startSharding(system, Some(SocketIOExtension(system).topicProps))

        // if it starts as the first node, should also start ConnectionSession's coordinate
        ConnectionSession.startSharding(system, None) 
      }

      // with roles: session, topic
      runOn(session1, session2) { 
        // if it starts as the first node, should also start topicAggregator's single manager 
        Topic.startTopicAggregator(system, role = Some("topic"))

        Topic.startSharding(system, None) 
        ConnectionSession.startSharding(system, Some(SocketIOExtension(system).sessionProps)) 
      } 

      // with roles: transport
      runOn(transport1) {
        ConnectionSession.startSharding(system, None) 

        val server = system.actorOf(Props(classOf[SocketIOServer], testActor), "socketio-server")
        IO(UHttp) ! Http.Bind(server, host, port1)
        expectMsgType[Tcp.Bound]
      }

      // with roles: transport 
      runOn(transport2) { 
        ConnectionSession.startSharding(system, None) 

        val server = system.actorOf(Props(classOf[SocketIOServer], testActor), "socketio-server")
        IO(UHttp) ! Http.Bind(server, host, port2)
        expectMsgType[Tcp.Bound]
      }


      runOn(topic1, topic2, session1, session2, transport1, transport2) {
        awaitAssert {
          self ! cluster.state.members.filter(_.status == MemberStatus.Up).size  
          expectMsg(12)
        }
        enterBarrier("start-cluster")
      }
      
      runOn(controller, business1, business2, business3, client1, client2) {
        enterBarrier("start-cluster")
      }
    }

    "verify cluster sevices" in within(30.seconds) {

      runOn(topic1, topic2) {
        // verify that topicAggregator singleton is accessible
        def topicAggregatorProxy = Topic(system).topicAggregatorProxy
        val topicsSource = Source.actorPublisher[Any](Queue.props[Any]())
        val topicsFlow = Flow[Any].to(Sink.ignore).runWith(topicsSource)
        topicAggregatorProxy ! Subscribe(Topic.EMPTY, topicsFlow)
        expectMsgType[SubscribeAck]
      }

      runOn(session1, session2) {
        // verify that topicRegion is accessible
        def topicRegion = Topic.shardRegion(system)
        log.info("topicRegion: {}", topicRegion)
        val msgSource = Source.actorPublisher[Any](Queue.props[Any]())
        val msgFlow = Flow[Any].to(Sink.ignore).runWith(msgSource)
        topicRegion ! Subscribe(socketio.topicForBroadcast("", ""), msgFlow)
        expectMsgType[SubscribeAck]
      }

     runOn(transport1, transport2) {
        // verify that sessionRegion is accessible
        def sessionRegion = ConnectionSession.shardRegion(system)
        log.info("sessionRegion: {}", sessionRegion)

        awaitAssert { // wait for sharding ready
          sessionRegion ! ConnectionSession.AskStatus("0")
          expectMsgType[ConnectionSession.Status]
        }
      }

      enterBarrier("verified-cluster-services")
    }

    var flowOfBusiness3: ActorRef = null 

    "start business sevices" in within(60.seconds) {

      runOn(business1) {
        val topicAggregatorClient = Topic(system).topicAggregatorClient

        val topicsSource = Source.actorPublisher[Any](Queue.props[Any]())
        val topicsSink = Sink.actorSubscriber(Props(new TopicAggregatorWorker(self)))
        val topicsFlow = Flow[Any].to(topicsSink).runWith(topicsSource)
        topicAggregatorClient ! Subscribe(Topic.EMPTY, topicsFlow)
        expectMsgType[SubscribeAck]

        expectNoMsg(10.seconds)

        val socketioExt = SocketIOExtension(system)

        val msgSource = Source.actorPublisher[Any](Queue.props[Any]())
        val msgSink = Sink.actorSubscriber(Props(new MsgWorker(socketioExt, self)))
        val msgFlow = Flow[Any].to(msgSink).runWith(msgSource)
        socketioExt.topicClient ! Subscribe(Topic.EMPTY, Some("group1"), msgFlow)

        // we'd expect 1 SubsribeAck and 1 Available
        expectMsgAllConformingOf(classOf[SubscribeAck], classOf[Aggregator.Available])

        topicAggregatorClient ! Aggregator.AskStats
        val stats = expectMsgType[Aggregator.Stats](10.seconds)
        log.info("aggregator topics: {}", stats)
        stats.reportingData.values.toList should contain(Topic.EMPTY)

        enterBarrier("subscribed-topicAggregator-singleton")
        enterBarrier("started-business")
      }

      runOn(business2) {
        enterBarrier("subscribed-topicAggregator-singleton")

        val topicAggregatorClient = Topic(system).topicAggregatorClient

        topicAggregatorClient ! Aggregator.AskStats
        val stats = expectMsgType[Aggregator.Stats](10.seconds)
        log.info("aggregator topics: {}", stats)
        stats.reportingData.values.toList should contain(Topic.EMPTY)

        val socketioExt = SocketIOExtension(system)

        val msgSource = Source.actorPublisher[Any](Queue.props[Any]())
        val msgSink = Sink.actorSubscriber(Props(new MsgWorker(socketioExt, self)))
        val msgFlow = Flow[Any].to(msgSink).runWith(msgSource)
        socketioExt.topicClient ! Subscribe(Topic.EMPTY, Some("group1"), msgFlow)
        expectMsgType[SubscribeAck]

        enterBarrier("started-business")
      }

      runOn(business3) {
        enterBarrier("subscribed-topicAggregator-singleton")

        val topicAggregatorClient = Topic(system).topicAggregatorClient

        topicAggregatorClient ! Aggregator.AskStats
        val stats = expectMsgType[Aggregator.Stats](10.seconds)
        log.info("aggregator topics: {}", stats)
        stats.reportingData.values.toList should contain(Topic.EMPTY)

        val socketioExt = SocketIOExtension(system)

        val msgSource = Source.actorPublisher[Any](Queue.props[Any]())
        val msgSink = Sink.actorSubscriber(Props(new MsgWorker(socketioExt, self)))
        val msgFlow = Flow[Any].to(msgSink).runWith(msgSource)
        socketioExt.topicClient ! Subscribe(Topic.EMPTY, Some("group2"), msgFlow)
        expectMsgType[SubscribeAck]

        flowOfBusiness3 = msgFlow 

        enterBarrier("started-business")
      }

      runOn(controller, transport1, transport2, session1, session2, topic1, topic2, client1, client2) {
        enterBarrier("subscribed-topicAggregator-singleton")
        enterBarrier("started-business")
      }
    }

    "chat between client1 and server1" in within(60.seconds) {

      runOn(client1) {
        val connect = Http.Connect(host, port1)
        val client = system.actorOf(Props(classOf[SocketIOClient], connect, testActor))
        expectMsg(OnOpen)
        client ! SendHello
        // we have two business groups, so should got two messages back
        expectMsg(SendHello)
        expectMsg(SendHello)
        expectNoMsg(2.seconds)

        enterBarrier("two-groups-tested")
        enterBarrier("one-group")

        client ! SendHello
        // because business nodes are now in one group, here should receive only one Hello
        expectMsg(SendHello)
        expectNoMsg(2.seconds) 
      }

      runOn(business3) {
        enterBarrier("two-groups-tested")

        val socketioExt = SocketIOExtension(system)
        socketioExt.topicClient ! Unsubscribe(Topic.EMPTY, Some("group2"), flowOfBusiness3)
        expectMsgType[UnsubscribeAck]

        enterBarrier("one-group")
      }

      runOn(controller, transport1, transport2, session1, session2, topic1, topic2, business1, business2, client2) {
        enterBarrier("two-groups-tested")
        enterBarrier("one-group")
      }

      enterBarrier("chated")
    }

    "broadcast" in within(60.seconds) {
      enterBarrier("chated")

      val msg = "hello world"
      runOn(client2) {
        val connect = Http.Connect(host, port2)
        val client = system.actorOf(Props(classOf[SocketIOClient], connect, testActor))
        expectMsg(OnOpen)

        enterBarrier("client2-started")

        expectMsg(msg)
      }

      runOn(client1) {
        val connect = Http.Connect(host, port1)
        val client = system.actorOf(Props(classOf[SocketIOClient], connect, testActor))
        expectMsg(OnOpen)

        enterBarrier("client2-started")

        client ! SocketIOClient.SendBroadcast(msg)
        expectMsg(msg)
      }

      runOn(controller, transport1, transport2, session1, session2, topic1, topic2, business1, business2, business3) {
        enterBarrier("client2-started")
      }

      enterBarrier("broadcasted")
    }

  }
}
