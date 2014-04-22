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
import akka.io.IO
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

object SocketIOClusterSpecConfig extends MultiNodeConfig {
  // first node is a special node for test spec
  val controller = role("controller")

  val transport1 = role("transport1")
  val transport2 = role("transport2")
  val connectionActive1 = role("connectionActive1")
  val connectionActive2 = role("connectionActive2")
  val business1 = role("business1")

  val client1 = role("client1")
  val client2 = role("client2")

  val host = "127.0.0.1"

  val port1 = 8081
  val port2 = 8082

  def waitForSeconds(secs: Int)(system: ActorSystem) {
    val p = Promise[Boolean]()
    import system.dispatcher
    system.scheduler.scheduleOnce(secs.seconds) { p.success(true) }
    Await.ready(p.future, (secs + 10).seconds)
  }

  commonConfig(ConfigFactory.parseString("""
    akka.loglevel = INFO
    akka.actor.provider = "akka.cluster.ClusterActorRefProvider"
    akka.persistence.journal.plugin = "akka.persistence.journal.leveldb-shared"
    akka.persistence.journal.leveldb-shared.store {
      native = off
      dir = "target/test-shared-journal"
    }
    akka.persistence.snapshot-store.local.dir = "target/test-snapshots"
    akka.contrib.cluster.sharding.role = "connectionActive"
    spray.socketio.mode = "cluster"
                                         """))

  nodeConfig(transport1, transport2) {
    ConfigFactory.parseString("""akka.cluster.roles =["transport"]""")
  }

  nodeConfig(connectionActive2) {
    ConfigFactory.parseString("""akka.cluster.roles = ["connectionActive"]""")
  }

  nodeConfig(connectionActive1) {
    ConfigFactory.parseString(
      """
        akka.remote.netty.tcp.port = 2551
        akka.cluster.roles = ["connectionActive"]
      """)
  }

  nodeConfig(business1) {
    ConfigFactory.parseString(
      """
        akka.cluster.roles = ["business"]
        spray.socketio {
            seed-nodes = ["akka.tcp://SocketIOClusterSpec@localhost:2551/user/receptionist"]
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

object SocketIOClusterSpec {
  object SocketIOServer {
    def props(resolver: ActorRef) = Props(classOf[SocketIOServer], resolver)
  }

  class SocketIOServer(val resolver: ActorRef) extends Actor with ActorLogging {

    def receive = {
      // when a new connection comes in we register a SocketIOConnection actor as the per connection handler
      case Http.Connected(remoteAddress, localAddress) =>
        val serverConnection = sender()
        val conn = context.actorOf(Props(classOf[SocketIOWorker], serverConnection, resolver))
        serverConnection ! Http.Register(conn)
    }

  }

  class SocketIOWorker(val serverConnection: ActorRef, val resolver: ActorRef) extends SocketIOServerConnection {

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

  class SocketIOClient(connect: Http.Connect, commander: ActorRef) extends SocketIOClientConnection {
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
      waitForSeconds(1)(system)
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

      runOn(connectionActive1, connectionActive2) {
        system.actorSelection(node(controller) / "user" / "store") ! Identify(None)
        val sharedStore = expectMsgType[ActorIdentity].ref.get
        SharedLeveldbJournal.setStore(sharedStore, system)
      }
      enterBarrier("setup-persistence")
    }

    "join cluster" in within(15.seconds) {
      join(transport1, transport1)
      join(transport2, transport1)
      join(connectionActive1, transport1)
      join(connectionActive2, transport1)

      enterBarrier("join-cluster")
    }

    "startup server" in within(15.seconds) {
      runOn(transport1) {
        val resolver = SocketIOExtension(system).resolver
        val server = system.actorOf(SocketIOServer.props(resolver), "socketio-server")
        IO(UHttp) ! Http.Bind(server, host, port1)
      }

      runOn(transport2) {
        val resolver = SocketIOExtension(system).resolver
        val server = system.actorOf(SocketIOServer.props(resolver), "socketio-server")
        IO(UHttp) ! Http.Bind(server, host, port2)
      }

      enterBarrier("startup-server")
    }

    "startup business" in within(25.seconds) {
      runOn(business1) {
        waitForSeconds(5)(system)
        val resolver = NamespaceExtension(system).resolver

        val observer = new Observer[OnData] {
          override def onNext(value: OnData) {
            value match {
              case x @ OnEvent("chat", args, context) =>
                value.replyEvent("chat", args)(resolver)
              case OnEvent("broadcast", args, context) =>
                val msg = spray.json.JsonParser(args).asInstanceOf[JsArray].elements.head.asInstanceOf[JsString].value
                value.broadcast("", MessagePacket(-1, false, value.endpoint, msg))(resolver)
              case _ =>
                println("observed: " + value)
            }
          }
        }

        val channel = Subject[OnData]()
        channel.subscribe(observer)

        NamespaceExtension(system).startNamespace("")
        NamespaceExtension(system).namespace("") ! Namespace.Subscribe(channel)
      }

      enterBarrier("startup-server")
    }

    "broadcast subscribers" in within(25.seconds) {
      runOn(connectionActive1) {
        val client = self
        system.actorOf(Props(new Actor {
          override def receive: Receive = {
            case seq: Seq[Subscription] => client ! seq
          }
        }), name="test")
      }

      runOn(connectionActive2) {
        waitForSeconds(6)(system)
        val subscriptions = Await.result(system.actorSelection(node(connectionActive2).toSerializationFormat + "user/" + SocketIOExtension.mediatorName).ask(GetSubscriptions)(5 seconds).mapTo[GetSubscriptionsAck], Duration.Inf)
        log.info("subscriptions: " + subscriptions.toString)
        import system.dispatcher
        system.actorSelection(node(connectionActive1).toSerializationFormat + "user/test").resolveOne()(5 seconds).onSuccess {
          case actor => actor ! subscriptions.subscriptions
        }
      }

      runOn(connectionActive1) {
        waitForSeconds(6)(system)
        val subscriptions = Await.result(system.actorSelection(node(connectionActive1).toSerializationFormat + "user/" + SocketIOExtension.mediatorName).ask(GetSubscriptions)(5 seconds).mapTo[GetSubscriptionsAck], Duration.Inf)
        log.info("subscriptions: " + subscriptions.toString)
        awaitAssert {
          within(10 seconds) {
            expectMsg(subscriptions.subscriptions)
          }
        }
      }
    }

    "chat with client1 and server1" in within(15.seconds) {
      runOn(client1) {
        waitForSeconds(5)(system)
        val connect = Http.Connect(host, port1)
        val testing = self
        val commander = system.actorOf(Props(new Actor {
          def receive = {
            case SocketIOClient.OnOpen        => sender() ! SocketIOClient.SendHello
            case x @ SocketIOClient.SendHello => testing ! x
          }
        }))
        val client = system.actorOf(Props(classOf[SocketIOClient], connect, commander))
        awaitAssert {
          within(10.second) {
            expectMsg(SocketIOClient.SendHello)
            client ! Http.CloseAll
          }
        }
      }

      enterBarrier("chat")
    }

    "broadcast" in within(15.seconds) {
      val msg = "hello world"
      runOn(client2) {
        waitForSeconds(5)(system)
        val connect = Http.Connect(host, port2)
        val testing = self
        val commander = system.actorOf(Props(new Actor {
          def receive = {
            case SocketIOClient.OnOpen =>
            case `msg`  => testing ! msg
          }
        }))
        val client = system.actorOf(Props(classOf[SocketIOClient], connect, commander))

        awaitAssert {
          within(10.second) {
            expectMsg(msg)
          }
        }
      }

      runOn(client1) {
        waitForSeconds(5)(system)
        val connect = Http.Connect(host, port1)
        val testing = self
        val commander = system.actorOf(Props(new Actor {
          def receive = {
            case SocketIOClient.OnOpen =>
              waitForSeconds(5)(system) // wait for client2 connected
              sender() ! SocketIOClient.SendBroadcast(msg)
            case `msg` => testing ! msg
          }
        }))
        val client = system.actorOf(Props(classOf[SocketIOClient], connect, commander))

        awaitAssert {
          within(10.second) {
            expectMsg(msg)
          }
        }
      }

      enterBarrier("broadcast")
    }

  }
}
