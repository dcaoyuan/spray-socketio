package spray.contrib.socketio.examples

import akka.actor.{ ActorSystem, Actor, Props, ActorLogging, ActorRef, Identify, ActorIdentity }
import akka.cluster.Cluster
import akka.contrib.pattern.ClusterSharding
import akka.io.IO
import akka.pattern.ask
import akka.persistence.Persistence
import akka.persistence.journal.leveldb.SharedLeveldbJournal
import akka.persistence.journal.leveldb.SharedLeveldbStore
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import rx.lang.scala.Observer
import scala.concurrent.duration._
import spray.can.Http
import spray.can.server.UHttp
import spray.can.websocket
import spray.can.websocket.frame.Frame
import spray.contrib.socketio
import spray.contrib.socketio.ConnectionActive
import spray.contrib.socketio.Namespace
import spray.contrib.socketio.Namespace.OnEvent
import spray.contrib.socketio.SocketIOServerConnection
import spray.contrib.socketio.cluster.ClusterConnectionActive
import spray.contrib.socketio.cluster.ClusterConnectionActiveSelector
import spray.contrib.socketio.cluster.ClusterNamespace
import spray.contrib.socketio.packet.EventPacket
import spray.http.{ HttpMethods, HttpRequest, Uri, HttpResponse, HttpEntity, ContentType, MediaTypes }
import spray.json.DefaultJsonProtocol

object SimpleClusterServer extends App with MySslConfiguration {

  class SocketIOServer extends Actor with ActorLogging {
    def receive = {
      // when a new connection comes in we register a SocketIOConnection actor as the per connection handler
      case Http.Connected(remoteAddress, localAddress) =>
        val serverConnection = sender()
        val conn = context.actorOf(Props(classOf[SocketIOWorker], serverConnection))
        serverConnection ! Http.Register(conn)
    }
  }

  val WEB_ROOT = "/home/dcaoyuan/myprjs/spray-socketio/src/main/scala/spray/contrib/socketio/examples"

  class SocketIOWorker(val serverConnection: ActorRef) extends SocketIOServerConnection {

    def genericLogic: Receive = {
      case HttpRequest(HttpMethods.GET, Uri.Path("/socketio.html"), _, _, _) =>
        val content = renderTextFile(WEB_ROOT + "/socketio.html")
        val entity = HttpEntity(ContentType(MediaTypes.`text/html`), content)
        sender() ! HttpResponse(entity = entity)

      case HttpRequest(HttpMethods.GET, Uri.Path("/jquery-1.7.2.min.js"), _, _, _) =>
        val content = renderTextFile(WEB_ROOT + "/jquery-1.7.2.min.js")
        val entity = HttpEntity(ContentType(MediaTypes.`application/javascript`), content)
        sender() ! HttpResponse(entity = entity)

      case HttpRequest(HttpMethods.GET, Uri.Path("/socket.io.js"), _, _, _) =>
        val content = renderTextFile(WEB_ROOT + "/socket.io.js")
        val entity = HttpEntity(ContentType(MediaTypes.`application/javascript`), content)
        sender() ! HttpResponse(entity = entity)

      case x: HttpRequest =>
        log.info("Got http req uri = {}", x.uri.path.toString.split("/").toList)

      case x: Frame =>
    }

    def renderTextFile(path: String) = {
      val source = scala.io.Source.fromFile(path)
      val lines = source.getLines mkString "\n"
      source.close()
      lines
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
  val systemConfig = ConfigFactory.parseString("akka.remote.netty.tcp.port=" + clusterPort).
    withFallback(ConfigFactory.load())

  implicit val system = ActorSystem("ClusterSystem", systemConfig)
  import system.dispatcher
  val clusterSystem = Cluster(system)
  // start the Persistence extension
  Persistence(system)
  startupSharedJournal(system, clusterSystem.selfAddress.port == Some(clusterPort), "store", clusterSystem.selfAddress + "/user/store")

  // join to cluster
  clusterSystem.join(clusterSystem.selfAddress)

  ClusterSharding(system).start(
    typeName = ConnectionActive.shardName,
    entryProps = Some(Props[ClusterConnectionActive]),
    idExtractor = ClusterConnectionActiveSelector.idExtractor,
    shardResolver = ClusterConnectionActiveSelector.shardResolver)

  ConnectionActive.init(new ClusterConnectionActiveSelector(system))
  Namespace.init(classOf[ClusterNamespace])

  // --- json protocals for socketio messages:
  case class Msg(message: String)
  case class Now(time: String)
  object TheJsonProtocol extends DefaultJsonProtocol {
    implicit val msgFormat = jsonFormat1(Msg)
    implicit val nowFormat = jsonFormat1(Now)
  }
  import spray.json._
  import TheJsonProtocol._

  val observer = Observer[OnEvent](
    (next: OnEvent) => {
      next match {
        case OnEvent("Hi!", args, context) =>
          println("observed: " + next.name + ", " + next.args)
          next.replyEvent("welcome", Msg("Greeting from spray-socketio").toJson.toString)(system)
          next.replyEvent("time", Now((new java.util.Date).toString).toJson.toString)(system)
          // batched packets
          next.reply(
            EventPacket(-1L, false, "testendpoint", "welcome", Msg("Batcher Greeting from spray-socketio").toJson.toString),
            EventPacket(-1L, false, "testendpoint", "time", Now("Batched " + (new java.util.Date).toString).toJson.toString))(system)
        case OnEvent("time", args, context) =>
          println("observed: " + next.name + ", " + next.args)
        case _ =>
          println("observed: " + next.name + ", " + next.args)
      }
    })

  Namespace.subscribe("testendpoint", observer)(system)
  val server = system.actorOf(Props(classOf[SocketIOServer]), "socketio")

  IO(UHttp) ! Http.Bind(server, "localhost", 8080)
}
