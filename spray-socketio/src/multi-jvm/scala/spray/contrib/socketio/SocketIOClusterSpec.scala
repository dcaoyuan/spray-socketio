package spray.contrib.socketio

import akka.actor.ActorIdentity
import akka.actor.Identify
import akka.actor._
import akka.cluster.Cluster
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
import spray.contrib.socketio.SocketIOClusterSpec.SocketIOClient.OnOpen
import spray.contrib.socketio.SocketIOClusterSpec.SocketIOClient.SendHello
import spray.contrib.socketio.namespace.Channel
import spray.contrib.socketio.namespace.Namespace
import spray.contrib.socketio.namespace.Namespace.OnData
import spray.contrib.socketio.namespace.Namespace.OnEvent
import spray.contrib.socketio.packet.{EventPacket, Packet, MessagePacket}
import spray.json.{JsArray, JsString}

object SocketIOClusterSpecConfig extends MultiNodeConfig {
  // first node is a special node for test spec
  val controller = role("controller")

  val transport1 = role("transport1")
  val transport2 = role("transport2")
  val session1 = role("session1")
  val session2 = role("session2")
  val namespace1 = role("namespace1")
  val namespace2 = role("namespace2")
  val business1 = role("business1")
  val business2 = role("business2")
  val business3 = role("business3")

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

  nodeConfig(transport1, transport2) {
    ConfigFactory.parseString(
      """
        akka.cluster.roles =["transport"]
      """)
  }

  nodeConfig(session1) {
    ConfigFactory.parseString(
      """
        akka.remote.netty.tcp.port = 2551
        akka.contrib.cluster.sharding.role = "session"
        akka.cluster.roles = ["stateful", "session"]
      """)
  }

  nodeConfig(session2) {
    ConfigFactory.parseString(
      """
        akka.contrib.cluster.sharding.role = "session"
        akka.cluster.roles = ["stateful", "session"]
      """)
  }

  nodeConfig(namespace1, namespace2) {
    ConfigFactory.parseString(
      """
        akka.contrib.cluster.sharding.role = "namespace"
        akka.cluster.roles =["stateful", "namespace"]
      """)
  }

  nodeConfig(business1, business2) {
    ConfigFactory.parseString(
      """
        akka.cluster.roles = ["business"]
        spray.socketio {
            cluster.client-initial-contacts = ["akka.tcp://SocketIOClusterSpec@localhost:2551/user/receptionist"]
        }
      """)

  }

  nodeConfig(business3) {
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

  class SocketIOServer(val sessionRegion: ActorRef, probe: ActorRef) extends Actor with ActorLogging {

    def receive = {
      case x: Tcp.Bound => probe ! x
        // when a new connection comes in we register a SocketIOConnection actor as the per connection handler

      case Http.Connected(remoteAddress, localAddress) =>
        val serverConnection = sender()
        val conn = context.actorOf(Props(classOf[SocketIOWorker], serverConnection, sessionRegion))
        serverConnection ! Http.Register(conn)
    }

  }

  class SocketIOWorker(val serverConnection: ActorRef, val sessionRegion: ActorRef) extends Actor with SocketIOServerWorker {

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
  
  class Receiver(socketioExt: SocketIOExtension) extends ActorSubscriber {
    val sessionClient = socketioExt.sessionClient
    override val requestStrategy = WatermarkRequestStrategy(10)
    def receive = {
      case OnNext(value @ OnEvent("chat", args, context)) =>
        value.replyEvent("chat", args)(sessionClient)
      case OnNext(value @ OnEvent("broadcast", args, context)) =>
        val msg = spray.json.JsonParser(args).asInstanceOf[JsArray].elements.head.asInstanceOf[JsString].value
        value.broadcast("", MessagePacket(-1, false, value.endpoint, msg))(sessionClient)
      case OnNext(value) =>
        println("observed: " + value)
    }
  }
}

class SocketIOClusterSpec extends MultiNodeSpec(SocketIOClusterSpecConfig) with STMultiNodeSpec with ImplicitSender {

  import SocketIOClusterSpecConfig._
  import SocketIOClusterSpec._

  override def initialParticipants: Int = roles.size

  def awaitCount(expected: Int): Unit = {
    val mediator = DistributedPubSubExtension(system).mediator
    awaitAssert {
      mediator ! Count
      expectMsgType[Int] should be(expected)
    }
  }

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

  def join(from: RoleName, to: RoleName)(starting: => Unit): Unit = {
    runOn(from) {
      starting
      Cluster(system) join node(to).address
    }
    enterBarrier(from.name + "-joined")
  }

  "Sharded socketio cluster" must {

    "setup shared journal" in {
      // start the Persistence extension
      Persistence(system)
      runOn(controller) {
        system.actorOf(Props[SharedLeveldbStore], "store")
      }
      enterBarrier("peristence-started")

      runOn(session1, session2, namespace1, namespace2) {
        system.actorSelection(node(controller) / "user" / "store") ! Identify(None)
        val sharedStore = expectMsgType[ActorIdentity].ref.get
        SharedLeveldbJournal.setStore(sharedStore, system)
      }
      enterBarrier("setup-persistence")
    }

    "join cluster" in within(15.seconds) {

      // 'session' sharding should start before sharding proxy, for instance, the sharding proxy on transport nodes.
      join(session1, session1) {
        val socketioExt = SocketIOExtension(system)
        ConnectionSession.startSharding(system, Some(socketioExt.sessionProps)) 
      }

      join(session2, session1) {
        val socketioExt = SocketIOExtension(system)
        ConnectionSession.startSharding(system, Some(socketioExt.sessionProps)) 
      }

      join(transport1, session1) {
        val socketioExt = SocketIOExtension(system)
        ConnectionSession.startSharding(system, None) 
      }

      join(transport2, session1) {
        val socketioExt = SocketIOExtension(system)
        ConnectionSession.startSharding(system, None) 
      }

      join(namespace1, session1) {
        val socketioExt = SocketIOExtension(system)
        Namespace.startSharding(system, Some(socketioExt.namespaceProps))
      }

      join(namespace2, session1) {
        val socketioExt = SocketIOExtension(system)
        Namespace.startSharding(system, Some(socketioExt.namespaceProps))
      } 
 
      runOn(transport1, transport2, session1, session2, namespace1, namespace2) {
        awaitCount(4)
      }
      enterBarrier("join-cluster")
    }

    "startup server" in within(15.seconds) {
      //runOn(session1, session2) {
      //  val socketioExt = SocketIOExtension(system)
      //  ConnectionSession.startSharding(system, Some(socketioExt.sessionProps)) 
      //}

      runOn(transport1) {
        val socketioExt = SocketIOExtension(system)
        //ConnectionSession.startSharding(system, None) 
        val sessionRegion = socketioExt.sessionRegion
        val server = system.actorOf(Props(classOf[SocketIOServer], sessionRegion, testActor), "socketio-server")
        IO(UHttp) ! Http.Bind(server, host, port1)
        expectMsgType[Tcp.Bound]
      }

      runOn(transport2) {
        val socketioExt = SocketIOExtension(system)
        //ConnectionSession.startSharding(system, None) 
        val sessionRegion = socketioExt.sessionRegion
        val server = system.actorOf(Props(classOf[SocketIOServer], sessionRegion, testActor), "socketio-server")
        IO(UHttp) ! Http.Bind(server, host, port2)
        expectMsgType[Tcp.Bound]
      }

      //runOn(namespace1, namespace2) {
      //}

      enterBarrier("startup-server")
    }

    var channelOfBusiness3: ActorRef = null 

    "startup business" in within(25.seconds) {
      runOn(business1, business2) {
        val socketioExt = SocketIOExtension(system)

        val channel = system.actorOf(Channel.props())
        val receiver = system.actorOf(Props(new Receiver(socketioExt)))
        ActorPublisher(channel).subscribe(ActorSubscriber(receiver))

        val namespaceClient = socketioExt.namespaceClient
        namespaceClient ! Subscribe(Namespace.GlobalTopic, Some("group1"), channel)
        expectMsgType[SubscribeAck]
      }

      runOn(business3) {
        val socketioExt = SocketIOExtension(system)

        val channel = system.actorOf(Channel.props())
        val receiver = system.actorOf(Props(new Receiver(socketioExt)))
        ActorPublisher(channel).subscribe(ActorSubscriber(receiver))

        val namespaceClient = socketioExt.namespaceClient
        namespaceClient ! Subscribe(Namespace.GlobalTopic, Some("group2"), channel)
        expectMsgType[SubscribeAck]

        channelOfBusiness3 = channel
      }

      enterBarrier("startup-server")
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

    "chat between client1 and server1" in within(25.seconds) {
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
        val namespaceClient = socketioExt.namespaceClient
        namespaceClient ! Unsubscribe(Namespace.GlobalTopic, Some("group2"), channelOfBusiness3)
        expectMsgType[UnsubscribeAck]
        enterBarrier("one-group")
      }

      runOn(controller, transport1, transport2, session1, session2, namespace1, namespace2, business1, business2, client2) {
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

      runOn(controller, transport1, transport2, session1, session2, namespace1, namespace2, business1, business2, business3) {
        enterBarrier("client2-started")
      }

      enterBarrier("broadcast")
    }

  }
}
