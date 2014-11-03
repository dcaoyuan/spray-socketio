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
import akka.stream.actor.ActorPublisher
import akka.stream.actor.ActorSubscriber
import akka.stream.actor.ActorSubscriberMessage.OnNext
import akka.stream.actor.WatermarkRequestStrategy
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
import spray.contrib.socketio.mq.Queue
import spray.contrib.socketio.mq.Topic
import spray.contrib.socketio.packet.{EventPacket, Packet, MessagePacket}
import spray.json.{JsArray, JsString}

object SocketIOClusterSpecConfig extends MultiNodeConfig {
  // first node is a special node for test spec
  val controller = role("controller")

  val topic1 = role("topic1")
  val topic2 = role("topic2")
  val transport1 = role("transport1")
  val transport2 = role("transport2")
  val session1   = role("session1")
  val session2   = role("session2")
  val business1  = role("business1")
  val business2  = role("business2")
  val business3  = role("business3")

  val client1 = role("client1")
  val client2 = role("client2")

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
    """))

  nodeConfig(topic1) {
    ConfigFactory.parseString(
      """
        akka.contrib.cluster.sharding.role = "topic"
        akka.cluster.roles = ["stateful", "topic"]
      """)
  }

  nodeConfig(topic2) {
    ConfigFactory.parseString(
      """
        akka.contrib.cluster.sharding.role = "topic"
        akka.cluster.roles = ["stateful", "topic"]
      """)
  }

  nodeConfig(session1) {
    ConfigFactory.parseString(
      """
        akka.remote.netty.tcp.port = 2551
        akka.contrib.cluster.sharding.role = "session"
        akka.cluster.roles = ["stateful", "session", "topic"]
      """)
  }

  nodeConfig(session2) {
    ConfigFactory.parseString(
      """
        akka.contrib.cluster.sharding.role = "session"
        akka.cluster.roles = ["stateful", "session", "topic"]
      """)
  }

  nodeConfig(transport1, transport2) {
    ConfigFactory.parseString(
      """
        akka.cluster.roles =["transport"]
      """)
  }

  nodeConfig(business1, business2, business3) {
    ConfigFactory.parseString(
      """
        akka.cluster.roles = ["business"]
        spray.socketio {
            cluster.client-initial-contacts = ["akka.tcp://SocketIOClusterSpec@localhost:2551/user/receptionist"]
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
  
  class Receiver(socketioExt: SocketIOExtension, probe: ActorRef) extends ActorSubscriber {
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

  class TopicEventSourceReceiver(probe: ActorRef) extends ActorSubscriber with ActorLogging {
    override val requestStrategy = WatermarkRequestStrategy(10)
    def receive = {
      case OnNext(value : Topic.TopicCreated) =>
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

    "setup shared journal" in {
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

    "join cluster" in within(30.seconds) {

      val cluster = Cluster(system)

      runOn(session1)   { cluster join node(session1).address }
      runOn(session2)   { cluster join node(session1).address }
      runOn(topic1) { cluster join node(session1).address }
      runOn(topic2) { cluster join node(session1).address }
      runOn(transport1) { cluster join node(session1).address }
      runOn(transport2) { cluster join node(session1).address }


      runOn(topic1, topic2, session1, session2, transport1, transport2) {
        awaitAssert {
          self ! cluster.state.members.filter(_.status == MemberStatus.Up).size  
          expectMsg(6)
        }
        enterBarrier("join-cluster")
      }
      
      runOn(controller, business1, business2, business3, client1, client2) {
        enterBarrier("join-cluster")
      }
    }

    "start cluster sevices" in within(30.seconds) {

      // The first started node should start all sharding sevices, no matter it start this sharding for entries or for proxy.
      // Since the sharding's singleton/coordinator will locate to oldest member.
      runOn(session1) {
        Topic.startSharding(system, None) 
        ConnectionSession.startSharding(system, Some(SocketIOExtension(system).sessionProps)) 
      }

      runOn(session2) {
        Topic.startSharding(system, None) 
        ConnectionSession.startSharding(system, Some(SocketIOExtension(system).sessionProps)) 
      }

      runOn(topic1) {
        Thread.sleep(5000)

        Topic.startSharding(system, Some(SocketIOExtension(system).topicProps))
      }

      runOn(topic2) {
        Thread.sleep(5000)

        Topic.startSharding(system, Some(SocketIOExtension(system).topicProps))
      } 

      runOn(transport1) {
        Thread.sleep(10000)

        ConnectionSession.startSharding(system, None) 

        val server = system.actorOf(Props(classOf[SocketIOServer], testActor), "socketio-server")
        IO(UHttp) ! Http.Bind(server, host, port1)
        expectMsgType[Tcp.Bound]
      }

      runOn(transport2) {
        Thread.sleep(10000)

        ConnectionSession.startSharding(system, None) 

        val server = system.actorOf(Props(classOf[SocketIOServer], testActor), "socketio-server")
        IO(UHttp) ! Http.Bind(server, host, port2)
        expectMsgType[Tcp.Bound]
      }

      enterBarrier("started-cluster-services")
    }

    "verify cluster sevices" in within(30.seconds) {

      runOn(session1) {
        val topicRegion = Topic.shardRegion(system)
        log.info("topicRegion: {}", topicRegion)
        topicRegion ! Identify(None) 
        expectMsgType[ActorIdentity]

        val queue = system.actorOf(Queue.props())
        topicRegion ! Subscribe(Topic.TopicEventSource, None, queue)
        expectMsgType[SubscribeAck]
      }

     runOn(transport1) {
        def sessionRegion = ConnectionSession.shardRegion(system)
        log.info("sessionRegion: {}", sessionRegion)
        sessionRegion ! ConnectionSession.AskStatus("0")
        expectMsgType[ConnectionSession.Status]
      }

      enterBarrier("verified-cluster-services")
    }

    var queueOfBusiness3: ActorRef = null 

    "start business sevices" in within(30.seconds) {

      runOn(business1, business2) {
        val nsqueue = system.actorOf(Queue.props())
        val nsreceiver = system.actorOf(Props(new TopicEventSourceReceiver(self)))
        ActorPublisher(nsqueue).subscribe(ActorSubscriber(nsreceiver))

        val socketioExt = SocketIOExtension(system)

        val queue = system.actorOf(Queue.props())
        val receiver = system.actorOf(Props(new Receiver(socketioExt, self)))
        ActorPublisher(queue).subscribe(ActorSubscriber(receiver))

        socketioExt.topicClient ! Subscribe(Topic.TopicEventSource, nsqueue)
        socketioExt.topicClient ! Subscribe(Topic.TopicEmpty, Some("group1"), queue)
        expectMsgAnyClassOf(classOf[Topic.TopicCreated], classOf[SubscribeAck], classOf[SubscribeAck])
      }

      runOn(business3) {
        val socketioExt = SocketIOExtension(system)

        val queue = system.actorOf(Queue.props())
        val receiver = system.actorOf(Props(new Receiver(socketioExt, self)))
        ActorPublisher(queue).subscribe(ActorSubscriber(receiver))

        socketioExt.topicClient ! Subscribe(Topic.TopicEmpty, Some("group2"), queue)
        expectMsgType[SubscribeAck]

        queueOfBusiness3 = queue
      }

      enterBarrier("started-business")
    }

    /*
     "broadcast subscribers" in within(25.seconds) {
     runOn(session1) {
     val client = self
     system.actorOf(Props(new Actor {
     override def receive: Receive = {
     case seq: Seq[_] => client ! seq.toSet
     }
     }), name="test")
     }

     runOn(session2) {
     akka://SocketIOClusterSpec/user/distributedPubSubMediator
     val subscriptions = Await.result(system.actorSelection(node(session2).toSerializationFormat + "user/" + SocketIOExtension.mediatorName).ask(GetSubscriptions)(5 seconds).mapTo[GetSubscriptionsAck], Duration.Inf)
     log.info("subscriptions: " + subscriptions.toString)
     import system.dispatcher
     system.actorSelection(node(session1).toSerializationFormat + "user/test").resolveOne()(5 seconds).onSuccess {
     case actor => actor ! subscriptions.subscriptions
     }
     }

     runOn(session1) {
     val subscriptions = Await.result(system.actorSelection(node(session1).toSerializationFormat + "user/" + SocketIOExtension.mediatorName).ask(GetSubscriptions)(5 seconds).mapTo[GetSubscriptionsAck], Duration.Inf)
     log.info("subscriptions: " + subscriptions.toString)
     expectMsg(subscriptions.subscriptions.toSet)
     }

     enterBarrier("broadcast-subscribers")
     }
     */

    "chat between client1 and server1" in within(30.seconds) {
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
        socketioExt.topicClient ! Unsubscribe(Topic.TopicEmpty, Some("group2"), queueOfBusiness3)
        expectMsgType[UnsubscribeAck]
        enterBarrier("one-group")
      }

      runOn(controller, transport1, transport2, session1, session2, topic1, topic2, business1, business2, client2) {
        enterBarrier("two-groups-tested")
        enterBarrier("one-group")
      }

      enterBarrier("chat")
    }

    "broadcast" in within(25.seconds) {
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

      enterBarrier("broadcast")
    }

  }
}
