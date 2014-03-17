package spray.contrib.socketio

import akka.remote.testkit.{ MultiNodeSpec, MultiNodeConfig }
import akka.testkit.ImplicitSender
import java.io.File
import org.iq80.leveldb.util.FileUtils
import akka.cluster.Cluster
import akka.actor._
import akka.contrib.pattern.{ ClusterReceptionistExtension, ClusterSharding }
import akka.persistence.journal.leveldb.{ SharedLeveldbJournal, SharedLeveldbStore }
import akka.persistence.Persistence
import scala.concurrent.duration._
import com.typesafe.config.ConfigFactory
import akka.io.IO
import spray.can.server.UHttp
import spray.can.Http
import spray.contrib.socketio.examples.benchmark.{ SocketIOTestClient, SocketIOTestServer }
import akka.actor.ActorIdentity
import akka.remote.testconductor.RoleName
import scala.concurrent.Await
import scala.concurrent.Promise
import akka.actor.Identify
import spray.contrib.socketio.examples.benchmark.SocketIOLoadTester.MessageArrived
import rx.lang.scala.Observer
import spray.contrib.socketio.namespace.Namespace
import spray.contrib.socketio.namespace.Namespace.OnEvent
import spray.contrib.socketio.packet.MessagePacket
import spray.contrib.socketio.extension.SocketIOExtension

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

  nodeConfig(connectionActive1, connectionActive2) {
    ConfigFactory.parseString("""akka.cluster.roles = ["connectionActive"]""")
  }

  nodeConfig(business1) {
    ConfigFactory.parseString("""akka.cluster.roles = ["business"]""")
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

class SocketIOClusterSpec extends MultiNodeSpec(SocketIOClusterSpecConfig) with STMultiNodeSpec with ImplicitSender {

  import SocketIOClusterSpecConfig._

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

    // optional: register cluster receptionist for cluster client
    ClusterReceptionistExtension(system).registerService(
      ClusterSharding(system).shardRegion(ConnectionActive.shardName))
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
      join(business1, transport1)

      enterBarrier("join-cluster")
    }

    "startup server" in within(15.seconds) {
      runOn(transport1) {
        val resolver = SocketIOExtension(system).resolver
        val server = system.actorOf(Props(classOf[SocketIOTestServer.SocketIOServer], resolver), "socketio-server")
        IO(UHttp) ! Http.Bind(server, host, port1)
      }

      runOn(transport2) {
        val resolver = SocketIOExtension(system).resolver
        val server = system.actorOf(Props(classOf[SocketIOTestServer.SocketIOServer], resolver), "socketio-server")
        IO(UHttp) ! Http.Bind(server, host, port2)
      }

      runOn(business1) {
        implicit val resolver = SocketIOExtension(system).resolver

        val observer = Observer[OnEvent](
          (next: OnEvent) => {
            next match {
              case OnEvent("chat", args, context) =>
                spray.json.JsonParser(args) // test spray-json too.
                next.replyEvent("chat", args)(resolver)
              case OnEvent("broadcast", args, context) =>
                next.broadcast("", MessagePacket(0, false, "", args))(resolver)
              case _ =>
                println("observed: " + next.name + ", " + next.args)
            }
          })

        SocketIOExtension(system).startNamespace()
        Namespace.subscribe(observer)(SocketIOExtension(system).namespace())
      }

      enterBarrier("startup-server")
    }

    "chat with client1 and server1" in within(15.seconds) {
      runOn(client1) {
        waitForSeconds(5)(system)
        val connect = Http.Connect(host, port1)
        val client = system.actorOf(Props(new SocketIOTestClient(connect, self)))

        awaitAssert {
          within(10.second) {
            client ! SocketIOTestClient.SendTimestampedChat
            expectMsgPF() {
              case MessageArrived(time) =>
                println("round time: " + time)
                client ! Http.CloseAll
            }
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
        val client = system.actorOf(Props(new SocketIOTestClient(connect, self)))

        awaitAssert {
          within(10.second) {
            expectMsg(msg)
          }
        }
      }

      runOn(client1) {
        waitForSeconds(5)(system)
        val connect = Http.Connect(host, port1)
        val client = system.actorOf(Props(new SocketIOTestClient(connect, self)))
        client ! SocketIOTestClient.SendBroadcast(msg)

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
