package spray.contrib.socketio

import akka.remote.testkit.{ MultiNodeSpec, MultiNodeConfig }
import akka.testkit.ImplicitSender
import java.io.File
import org.iq80.leveldb.util.FileUtils
import akka.cluster.Cluster
import akka.actor._
import akka.persistence.journal.leveldb.{ SharedLeveldbJournal, SharedLeveldbStore }
import akka.persistence.Persistence
import akka.pattern.ask
import scala.concurrent.duration._
import com.typesafe.config.ConfigFactory
import akka.io.{Tcp, IO}
import spray.can.server.UHttp
import spray.can.Http
import spray.contrib.socketio.namespace.Namespace
import spray.contrib.socketio.namespace.Namespace.OnData
import spray.contrib.socketio.namespace.Namespace.OnEvent
import spray.contrib.socketio.namespace.NamespaceExtension
import spray.contrib.socketio.packet.{EventPacket, Packet, MessagePacket}
import spray.json.{JsArray, JsString}
import akka.actor.ActorIdentity
import akka.remote.testconductor.RoleName
import scala.concurrent.Await
import scala.concurrent.Promise
import akka.actor.Identify
import rx.lang.scala.Subject
import rx.lang.scala.Observer
import spray.contrib.socketio.DistributedBalancingPubSubMediator.Internal.{Subscription, GetSubscriptionsAck, GetSubscriptions}
import spray.can.websocket.frame.{TextFrame, Frame}
import akka.contrib.pattern.DistributedPubSubExtension
import akka.contrib.pattern.DistributedPubSubMediator.Count
import spray.contrib.socketio.SocketIOClusterSpec.SocketIOClient.OnOpen

object SocketIOClusterSpecConfig extends MultiNodeConfig {
  // first node is a special node for test spec
  val controller = role("controller")

  val transport1 = role("transport1")
  val transport2 = role("transport2")
  val connectionSession1 = role("connectionSession1")
  val connectionSession2 = role("connectionSession2")
  val business1 = role("business1")
  val business2 = role("business2")
  val business3 = role("business3")

  val client1 = role("client1")
  val client2 = role("client2")

  val host = "127.0.0.1"

  val port1 = 8081
  val port2 = 8082

  commonConfig(ConfigFactory.parseString("""
    akka.loglevel = INFO
    akka.actor.provider = "akka.cluster.ClusterActorRefProvider"
    akka.extensions = ["akka.contrib.pattern.ClusterReceptionistExtension"]
    akka.persistence.journal.plugin = "akka.persistence.journal.leveldb-shared"
    akka.persistence.journal.leveldb-shared.store {
      native = off
      dir = "target/test-shared-journal"
    }
    akka.persistence.snapshot-store.local.dir = "target/test-snapshots"
    akka.contrib.cluster.sharding.role = "connectionSession"
    spray.socketio.mode = "cluster"
                                         """))

  nodeConfig(transport1, transport2) {
    ConfigFactory.parseString("""akka.cluster.roles =["transport"]""")
  }

  nodeConfig(connectionSession2) {
    ConfigFactory.parseString("""akka.cluster.roles = ["connectionSession"]""")
  }

  nodeConfig(connectionSession1) {
    ConfigFactory.parseString(
      """
        akka.remote.netty.tcp.port = 2551
        akka.cluster.roles = ["connectionSession"]
      """)
  }

  nodeConfig(business1, business2) {
    ConfigFactory.parseString(
      """
        akka.cluster.roles = ["business"]
        spray.socketio {
            cluster.client-initial-contacts = ["akka.tcp://SocketIOClusterSpec@localhost:2551/user/receptionist"]
            server.namespace-group-name = "group1"
        }
      """)

  }

  nodeConfig(business3) {
    ConfigFactory.parseString(
      """
        akka.cluster.roles = ["business"]
        spray.socketio {
            cluster.client-initial-contacts = ["akka.tcp://SocketIOClusterSpec@localhost:2551/user/receptionist"]
            server.namespace-group-name = "group2"
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

object SocketIOClusterSpec {
  object SocketIOServer {
    def props(sessionRegion: ActorRef, commander:ActorRef) = Props(classOf[SocketIOServer], sessionRegion, commander)
  }

  class SocketIOServer(val sessionRegion: ActorRef, val commander: ActorRef) extends Actor with ActorLogging {

    def receive = {
      case x: Tcp.Bound => commander ! x
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

  class SocketIOClient(connect: Http.Connect, commander: ActorRef) extends Actor with SocketIOClientWorker {
    import SocketIOClient._

    import context.system
    IO(UHttp) ! connect

    def businessLogic: Receive = {
      case SendHello           => connection ! TextFrame("5:::{\"name\":\"chat\", \"args\":[]}")
      case SendBroadcast(msg)  => connection ! TextFrame("""5:::{"name":"broadcast", "args":[""" + "\"" + msg + "\"" + "]}")
    }

    override def onDisconnected(endpoint: String) {
      commander ! OnClose
    }

    override def onOpen() {
      commander ! OnOpen
    }

    def onPacket(packet: Packet) {
      packet match {
        case EventPacket("chat", args) => commander ! SendHello
        case msg: MessagePacket => commander ! msg.data
        case _ =>
      }

    }
  }
}

class SocketIOClusterSpec extends MultiNodeSpec(SocketIOClusterSpecConfig) with STMultiNodeSpec with ImplicitSender {

  import SocketIOClusterSpecConfig._
  import SocketIOClusterSpec._

  override def initialParticipants: Int = roles.size

  def mediator: ActorRef = DistributedPubSubExtension(system).mediator

  def awaitCount(expected: Int): Unit = {
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

  def join(from: RoleName, to: RoleName): Unit = {
    runOn(from) {
      Cluster(system) join node(to).address
      startSharding()
    }
    enterBarrier(from.name + "-joined")
  }

  def startSharding(): Unit = {
    // start shard region actor
    SocketIOExtension(system)
  }

  "Sharded socketio cluster" must {

    "setup shared journal" in {
      // start the Persistence extension
      Persistence(system)
      runOn(controller) {
        system.actorOf(Props[SharedLeveldbStore], "store")
      }
      enterBarrier("peristence-started")

      runOn(connectionSession1, connectionSession2) {
        system.actorSelection(node(controller) / "user" / "store") ! Identify(None)
        val sharedStore = expectMsgType[ActorIdentity].ref.get
        SharedLeveldbJournal.setStore(sharedStore, system)
      }
      enterBarrier("setup-persistence")
    }

    "join cluster" in within(15.seconds) {
      join(transport1, transport1)
      join(transport2, transport1)
      join(connectionSession1, transport1)
      join(connectionSession2, transport1)

      runOn(transport1, transport2, connectionSession1, connectionSession2) {
        awaitCount(8)
      }
      enterBarrier("join-cluster")
    }

    "startup server" in within(15.seconds) {
      runOn(transport1) {
        val sessionRegion = SocketIOExtension(system).sessionRegion
        val server = system.actorOf(SocketIOServer.props(sessionRegion, self), "socketio-server")
        IO(UHttp) ! Http.Bind(server, host, port1)
        awaitAssert {
          expectMsgType[Tcp.Bound]
        }
      }

      runOn(transport2) {
        val sessionRegion = SocketIOExtension(system).sessionRegion
        val server = system.actorOf(SocketIOServer.props(sessionRegion, self), "socketio-server")
        IO(UHttp) ! Http.Bind(server, host, port2)
        awaitAssert {
          expectMsgType[Tcp.Bound]
        }
      }

      enterBarrier("startup-server")
    }

    "startup business" in within(25.seconds) {
      runOn(business1, business2, business3) {
        val sessionRegion = NamespaceExtension(system).sessionRegion

        val observer = new Observer[OnData] {
          override def onNext(value: OnData) {
            value match {
              case OnEvent("chat", args, context) =>
                value.replyEvent("chat", args)(sessionRegion)
              case OnEvent("broadcast", args, context) =>
                val msg = spray.json.JsonParser(args).asInstanceOf[JsArray].elements.head.asInstanceOf[JsString].value
                value.broadcast("", MessagePacket(-1, false, value.endpoint, msg))(sessionRegion)
              case _ =>
                println("observed: " + value)
            }
          }
        }

        val channel = Subject[OnData]()
        channel.subscribe(observer)

        NamespaceExtension(system).startNamespace("")
        NamespaceExtension(system).namespace("") ! Namespace.Subscribe(channel)
        awaitAssert {
          expectMsgType[Namespace.SubscribeAck]
        }
      }

      enterBarrier("startup-server")
    }

    "broadcast subscribers" in within(25.seconds) {
      runOn(connectionSession1) {
        val client = self
        system.actorOf(Props(new Actor {
          override def receive: Receive = {
            case seq: Seq[Subscription] => client ! seq.toSet
          }
        }), name="test")
      }

      runOn(connectionSession2) {
        val subscriptions = Await.result(system.actorSelection(node(connectionSession2).toSerializationFormat + "user/" + SocketIOExtension.mediatorName).ask(GetSubscriptions)(5 seconds).mapTo[GetSubscriptionsAck], Duration.Inf)
        log.info("subscriptions: " + subscriptions.toString)
        import system.dispatcher
        system.actorSelection(node(connectionSession1).toSerializationFormat + "user/test").resolveOne()(5 seconds).onSuccess {
          case actor => actor ! subscriptions.subscriptions
        }
      }

      runOn(connectionSession1) {
        val subscriptions = Await.result(system.actorSelection(node(connectionSession1).toSerializationFormat + "user/" + SocketIOExtension.mediatorName).ask(GetSubscriptions)(5 seconds).mapTo[GetSubscriptionsAck], Duration.Inf)
        log.info("subscriptions: " + subscriptions.toString)
        awaitAssert {
           expectMsg(subscriptions.subscriptions.toSet)
        }
      }

      enterBarrier("broadcast-subscribers")
    }

    "chat with client1 and server1" in within(25.seconds) {
      runOn(client1) {
        val connect = Http.Connect(host, port1)
        val client = system.actorOf(Props(classOf[SocketIOClient], connect, self))
        awaitAssert {
          expectMsg(SocketIOClient.OnOpen)
          client ! SocketIOClient.SendHello
          // we have two business groups, so got two messages back
          expectMsg(SocketIOClient.SendHello)
          expectMsg(SocketIOClient.SendHello)
          expectNoMsg(2 seconds)
          enterBarrier("two-groups-tested")
          enterBarrier("one-group")
          client ! SocketIOClient.SendHello
          expectMsg(SocketIOClient.SendHello)
          expectNoMsg(2 seconds) // because business nodes are in the same group, here only receive one Hello
        }
      }

      runOn(business3) {
        enterBarrier("two-groups-tested")
        NamespaceExtension(system).namespace("") ! Namespace.Unsubscribe(None)
        awaitAssert {
          expectMsgType[Namespace.UnsubscribeAck]
        }
        enterBarrier("one-group")
      }

      runOn(controller, transport1, transport2, connectionSession1, connectionSession2, business1, business2, client2) {
        enterBarrier("two-groups-tested")
        enterBarrier("one-group")
      }
      enterBarrier("chat")
    }

    "broadcast" in within(15.seconds) {
      val msg = "hello world"
      runOn(client2) {
        val connect = Http.Connect(host, port2)
        system.actorOf(Props(classOf[SocketIOClient], connect, self), name = "client2")
        awaitAssert {
          expectMsg(OnOpen)
          enterBarrier("client2-started")
          expectMsg(msg)
        }
      }

      runOn(client1) {
        val connect = Http.Connect(host, port1)
        val client = system.actorOf(Props(classOf[SocketIOClient], connect, self), name = "client1")

        awaitAssert {
          expectMsg(OnOpen)
          enterBarrier("client2-started")
          client ! SocketIOClient.SendBroadcast(msg)
          expectMsg(msg)
        }
      }

      runOn(controller, transport1, transport2, connectionSession1, connectionSession2, business1, business2, business3) {
        enterBarrier("client2-started")
      }

      enterBarrier("broadcast")
    }

  }
}
