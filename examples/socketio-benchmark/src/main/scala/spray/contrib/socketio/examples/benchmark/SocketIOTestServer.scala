package spray.contrib.socketio.examples.benchmark

import akka.actor.{ Terminated, ActorSystem, Actor, Props, ActorLogging, ActorRef }
import akka.contrib.pattern.DistributedPubSubMediator.Subscribe
import akka.io.IO
import akka.stream.actor.ActorSubscriber
import akka.stream.actor.ActorSubscriberMessage.OnNext
import akka.stream.ActorFlowMaterializer
import akka.stream.actor.WatermarkRequestStrategy
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import com.typesafe.config.ConfigFactory
import scala.concurrent.Future
import scala.concurrent.duration._
import spray.can.Http
import spray.can.server.UHttp
import spray.can.websocket.frame.Frame
import spray.contrib.socketio
import spray.contrib.socketio.ConnectionSession.OnEvent
import spray.contrib.socketio.SocketIOExtension
import spray.contrib.socketio.SocketIOServerWorker
import spray.contrib.socketio.mq.Queue
import spray.contrib.socketio.mq.Topic
import spray.contrib.socketio.packet.EventPacket
import spray.http.HttpRequest

object SocketIOTestServer extends App {

  case object COUNT

  object SocketIOServer {
    def props() = Props(classOf[SocketIOServer])
  }

  class SocketIOServer() extends Actor with ActorLogging {

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
        val conn = context.actorOf(SocketIOWorker.props(serverConnection))
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
    def props(serverConnection: ActorRef) = Props(classOf[SocketIOWorker], serverConnection)

    private var _sessionId = 0
    private val sessionIdMutex = new AnyRef
    def nextSessionId() = sessionIdMutex synchronized {
      _sessionId += 1
      _sessionId
    }
  }
  final class SocketIOWorker(val serverConnection: ActorRef) extends Actor with SocketIOServerWorker {
    def sessionRegion = SocketIOExtension(context.system).sessionRegion

    override def sessionIdGenerator: HttpRequest => Future[String] = { req =>
      Future.successful(SocketIOWorker.nextSessionId().toString)
    }

    def genericLogic: Receive = {
      case x: Frame =>
    }
  }

  implicit val system = ActorSystem()
  val socketioExt = SocketIOExtension(system)

  class MsgWorker extends ActorSubscriber {
    implicit val sessionClient = socketioExt.sessionClient

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

  // use queue publisher as input Source to a Flow, and worker subscriber as output Sink to this Flow.
  implicit val materializer = ActorFlowMaterializer()
  val msgSource = Source.actorPublisher(Queue.props[Any]())
  val msgSink = Sink.actorSubscriber(Props(new MsgWorker))
  val msgFlow = Flow[Any].to(msgSink).runWith(msgSource)

  socketioExt.topicClient ! Subscribe(Topic.EMPTY, None, msgFlow)

  val server = system.actorOf(SocketIOServer.props(), name = "socketio-server")

  val config = ConfigFactory.load().getConfig("spray.socketio.benchmark")
  val host = config.getString("server.host")
  val port = config.getInt("server.port")
  val isBroadcast = config.getBoolean("broadcast")
  IO(UHttp) ! Http.Bind(server, host, port)
}
