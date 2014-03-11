package spray.contrib.socketio

import akka.remote.testkit.{MultiNodeSpec, MultiNodeConfig}
import akka.testkit.ImplicitSender
import java.io.File
import org.iq80.leveldb.util.FileUtils
import akka.cluster.Cluster
import akka.actor._
import akka.contrib.pattern.{ClusterReceptionistExtension, ClusterSharding}
import akka.persistence.journal.leveldb.{SharedLeveldbJournal, SharedLeveldbStore}
import akka.persistence.Persistence
import scala.concurrent.duration._
import com.typesafe.config.ConfigFactory
import akka.io.IO
import spray.can.server.UHttp
import spray.can.Http
import spray.contrib.socketio.examples.benchmark.{SocketIOTestClient, SocketIOTestServer}
import akka.actor.ActorIdentity
import akka.remote.testconductor.RoleName
import scala.Some
import akka.actor.Identify
import spray.contrib.socketio.examples.benchmark.SocketIOLoadTester.MessageArrived
import rx.lang.scala.Observer
import spray.contrib.socketio.Namespace.OnEvent
import spray.contrib.socketio.cluster.{ClusterConnectionActiveSelector, ClusterNamespace, ClusterConnectionActive}

object SocketIOClusterSpecConfig extends MultiNodeConfig {
  // first node is a special node for test spec
  val controller = role("controller")

  val transport1 = role("transport1")
  val transport2 = role("transport2")
  val connectionActive1 = role("connectionActive1")
  val connectionActive2 = role("connectionActive2")
  val business1 = role("business1")

  val client = role("client")

  val host = "127.0.0.1"

  val port1 = 8081
  val port2 = 8082

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
      Thread.sleep(1000)
      Cluster(system) join node(to).address
      startSharding()
    }
    enterBarrier(from.name + "-joined")
  }

  def startSharding(): Unit = {
    // start shard region actor
    ClusterSharding(system).start(
      typeName = ConnectionActive.shardName,
      entryProps = Some(Props(classOf[ClusterConnectionActive], new ClusterConnectionActiveSelector(system))),
      idExtractor = ClusterConnectionActiveSelector.idExtractor,
      shardResolver = ClusterConnectionActiveSelector.shardResolver)

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
        val selection = new ClusterConnectionActiveSelector(system)
        val server = system.actorOf(Props(classOf[SocketIOTestServer.SocketIOServer], selection), "socketio")
        IO(UHttp) ! Http.Bind(server, host, port1)
      }

      runOn(transport2) {
        val selection = new ClusterConnectionActiveSelector(system)
        val server = system.actorOf(Props(classOf[SocketIOTestServer.SocketIOServer], selection), "socketio")
        IO(UHttp) ! Http.Bind(server, host, port2)
      }

      runOn(business1) {
        implicit val selection = new ClusterConnectionActiveSelector(system)

        val observer = Observer[OnEvent](
          (next: OnEvent) => {
            next match {
              case OnEvent("chat", args, context) =>
                next.replyEvent("chat", args: _*)
              case _ =>
                println("observed: " + next.name + ", " + next.args)
            }
          })

        Namespace.subscribe(Namespace.DEFAULT_NAMESPACE, observer)(system, Props(classOf[ClusterNamespace], Namespace.DEFAULT_NAMESPACE))
      }

      enterBarrier("startup-server")
    }

    "chat with client and server1" in within(15.seconds) {
      runOn(client) {
        Thread.sleep(5000)
        val connect = Http.Connect(host, port1)
        val client = system.actorOf(Props(new SocketIOTestClient(connect, self)))

        awaitAssert {
          within(10.second) {
            client ! SocketIOTestClient.SendTimestampedChat
            expectMsgPF() {
              case MessageArrived(time) => println("round time: " + time)
            }
          }
        }
      }

      enterBarrier("finish")
    }

  }
}
