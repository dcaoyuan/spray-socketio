package spray.contrib.socketio.examples.benchmark

import akka.io.IO
import akka.actor.{ ActorSystem, Actor, Props, ActorLogging, ActorRef, ActorIdentity, Identify }
import akka.contrib.pattern.{ ClusterSharding }
import akka.cluster.Cluster
import akka.pattern.ask
import akka.persistence.Persistence
import akka.persistence.journal.leveldb.{ SharedLeveldbJournal, SharedLeveldbStore }
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import spray.can.Http
import spray.can.server.UHttp
import spray.can.websocket.frame.Frame
import spray.contrib.socketio.{ ConnectionActiveSelector, ConnectionActive, SocketIOServerConnection, Namespace }
import spray.contrib.socketio.Namespace.OnEvent
import spray.contrib.socketio.cluster.ClusterConnectionActive
import spray.contrib.socketio.cluster.ClusterConnectionActiveSelector
import spray.contrib.socketio.cluster.ClusterNamespace
import rx.lang.scala.Observer
import scala.concurrent.duration._

object SocketIOClusterTestServer extends App {

  class SocketIOServer(selector: ConnectionActiveSelector) extends Actor with ActorLogging {
    def receive = {
      // when a new connection comes in we register a SocketIOConnection actor as the per connection handler
      case Http.Connected(remoteAddress, localAddress) =>
        val serverConnection = sender()
        val conn = context.actorOf(Props(classOf[SocketIOWorker], serverConnection, selector))
        serverConnection ! Http.Register(conn)
    }
  }

  class SocketIOWorker(val serverConnection: ActorRef, val selector: ConnectionActiveSelector) extends SocketIOServerConnection {

    def genericLogic: Receive = {
      case x: Frame =>
    }
  }

  def startupSharedJournal(system: ActorSystem, startStore: Boolean, name: String, path: String) {
    // Start the shared journal on one node (don't crash this SPOF)
    // This will not be needed with a distributed journal
    if (startStore)
      system.actorOf(Props[SharedLeveldbStore], name)
    // register the shared journal
    import system.dispatcher
    implicit val timeout = Timeout(1.minute)
    val f = (system.actorSelection(path) ? Identify(None))
    f.onSuccess {
      case ActorIdentity(_, Some(ref)) => SharedLeveldbJournal.setStore(ref, system)
      case _ =>
        system.log.error("Shared journal not started at {}", path)
        system.shutdown()
    }
    f.onFailure {
      case _ =>
        system.log.error("Lookup of shared journal at {} timed out", path)
        system.shutdown()
    }
  }

  val clusterPort = 2551
  // Override the configuration of the port
  val systemConfig = ConfigFactory.parseString("akka.remote.netty.tcp.port=" + clusterPort).withFallback(ConfigFactory.load())
  implicit val system = ActorSystem("ClusterSystem", systemConfig)
  implicit val selector = ClusterConnectionActiveSelector(system)

  val observer = Observer[OnEvent](
    (next: OnEvent) => {
      next match {
        case OnEvent("chat", args, context) =>
          next.replyEvent("chat", args)
        case _ =>
          println("observed: " + next.name + ", " + next.args)
      }
    })

  import system.dispatcher
  val clusterSystem = Cluster(system)

  // start the Persistence extension
  Persistence(system)
  startupSharedJournal(system, clusterSystem.selfAddress.port == Some(clusterPort), "store", clusterSystem.selfAddress + "/user/store")

  // join to cluster
  clusterSystem.join(clusterSystem.selfAddress)

  ClusterSharding(system).start(
    typeName = ConnectionActive.shardName,
    entryProps = Some(Props(classOf[ClusterConnectionActive], selector)),
    idExtractor = ClusterConnectionActiveSelector.idExtractor,
    shardResolver = ClusterConnectionActiveSelector.shardResolver)

  Namespace.subscribe(Namespace.DEFAULT_NAMESPACE, observer)(system, Props(classOf[ClusterNamespace], ""))
  val server = system.actorOf(Props(classOf[SocketIOServer], selector), name = "socketio")

  val config = ConfigFactory.load().getConfig("spray.socketio.benchmark")
  val host = config.getString("host")
  val port = config.getInt("port")
  IO(UHttp) ! Http.Bind(server, host, port)
}
