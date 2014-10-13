package spray.contrib.socketio.examples.benchmark

import akka.io.IO
import akka.actor.{ Terminated, ActorSystem, Actor, Props, ActorLogging, ActorRef }
import akka.stream.actor.ActorSubscriber
import akka.stream.actor.ActorSubscriberMessage.OnNext
import akka.stream.actor.ActorPublisher
import akka.stream.actor.WatermarkRequestStrategy
import com.typesafe.config.ConfigFactory
import scala.concurrent.Future
import scala.concurrent.duration._
import spray.can.Http
import spray.can.server.UHttp
import spray.can.websocket.frame.Frame
import spray.contrib.socketio.SocketIOExtension
import spray.contrib.socketio.SocketIOServerWorker
import spray.contrib.socketio.namespace.Channel
import spray.contrib.socketio.namespace.Namespace
import spray.contrib.socketio.namespace.Namespace.{ OnData, OnEvent }
import spray.contrib.socketio.namespace.NamespaceExtension
import spray.contrib.socketio.packet.EventPacket
import spray.http.HttpRequest

object SocketIOTestServer extends App {

  case object COUNT

  object SocketIOServer {
    def props(sessionRegion: ActorRef) = Props(classOf[SocketIOServer], sessionRegion)
  }

  class SocketIOServer(val sessionRegion: ActorRef) extends Actor with ActorLogging {
    var connected = 0
    var preconnected = 0

    override def preStart() {
      import context.dispatcher
      context.system.scheduler.schedule(0 seconds, 5 seconds, self, COUNT)
    }

    def receive = {
      // when a new connection comes in we register a SocketIOConnection actor as the per connection handler
      case Http.Connected(remoteAddress, localAddress) =>
        val serverConnection = sender()
        val conn = context.actorOf(SocketIOWorker.props(serverConnection, sessionRegion))
        context watch conn
        connected += 1
        serverConnection ! Http.Register(conn)

      case COUNT =>
        if (preconnected != connected) {
          preconnected = connected
          log.info("connected: " + connected)
        }

      case Terminated(ref) =>
        log.info("terminated: " + ref)
        context unwatch ref
        connected -= 1
    }
  }

  object SocketIOWorker {
    def props(serverConnection: ActorRef, sessionRegion: ActorRef) = Props(classOf[SocketIOWorker], serverConnection, sessionRegion)

    private var _sessionId = 0
    private val sessionIdMutex = new AnyRef
    def nextSessionId() = sessionIdMutex synchronized {
      _sessionId += 1
      _sessionId
    }
  }
  final class SocketIOWorker(val serverConnection: ActorRef, val sessionRegion: ActorRef) extends Actor with SocketIOServerWorker {

    override def sessionIdGenerator: HttpRequest => Future[String] = { req =>
      Future.successful(SocketIOWorker.nextSessionId().toString)
    }

    def genericLogic: Receive = {
      case x: Frame =>
    }
  }

  implicit val system = ActorSystem()
  SocketIOExtension(system)

  class Receiver extends ActorSubscriber {
    implicit val sessionRegion = NamespaceExtension(system).sessionRegion

    override val requestStrategy = WatermarkRequestStrategy(10)

    def receive = {
      case OnNext(value @ OnEvent("chat", args, context)) =>
        spray.json.JsonParser(args) // test spray-json performance too.
        if (isBroadcast) {
          value.broadcast("", EventPacket(-1L, false, value.endpoint, "chat", args))
        } else {
          value.replyEvent("chat", args)
        }

      case OnNext(value) =>
        println("observed: " + value)

    }
  }

  val channel = system.actorOf(Channel.props())
  val receiver = system.actorOf(Props(new Receiver))
  ActorPublisher(channel).subscribe(ActorSubscriber(receiver))

  NamespaceExtension(system).startNamespace("")
  NamespaceExtension(system).namespace("") ! Namespace.Subscribe(channel)

  val sessionRegionForTransport = SocketIOExtension(system).sessionRegion
  val server = system.actorOf(SocketIOServer.props(sessionRegionForTransport), name = "socketio-server")

  val config = ConfigFactory.load().getConfig("spray.socketio.benchmark")
  val host = config.getString("server.host")
  val port = config.getInt("server.port")
  val isBroadcast = config.getBoolean("broadcast")
  IO(UHttp) ! Http.Bind(server, host, port)
}
