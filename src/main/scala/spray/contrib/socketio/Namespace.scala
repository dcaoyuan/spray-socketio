package spray.contrib.socketio

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Cancellable
import akka.actor.PoisonPill
import akka.actor.Props
import akka.actor.Terminated
import rx.lang.scala.Observer
import rx.lang.scala.Subject
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration._
import scala.reflect.runtime.universe._
import spray.contrib.socketio
import spray.contrib.socketio.SocketIOConnection.Pause
import spray.contrib.socketio.SocketIOConnection.ReclockHeartbeatTimeout
import spray.contrib.socketio.packet.ConnectPacket
import spray.contrib.socketio.packet.DisconnectPacket
import spray.contrib.socketio.packet.EventPacket
import spray.contrib.socketio.packet.HeartbeatPacket
import spray.contrib.socketio.packet.JsonPacket
import spray.contrib.socketio.packet.MessagePacket
import spray.contrib.socketio.packet.Packet
import spray.json.JsValue

object Namespace {
  val DEFAULT_NAMESPACE = "socket.io"

  // TODO inject actor system
  val namespaces = ActorSystem().actorOf(Props[Namespace.Namespaces], name = "namespaces")

  // transportActor -> SocketIOContext
  private val allConnections = new TrieMap[ActorRef, SocketIOContext]()
  private val authorizedSessionIds = new TrieMap[String, Either[Cancellable, SocketIOContext]]()
  def authorize(ctx: SocketIOContext): Boolean = {
    authorizedSessionIds.get(ctx.sessionId) match {
      case Some(Left(timeout)) =>
        timeout.cancel
        true
      case Some(Right(soContext)) => // already occupied by socketio connection. TODO
        false
      case None =>
        false
    }
  }

  final case class Remove(namespace: String)
  final case class Session(sessionId: String)
  final case class Connected(soContext: SocketIOContext)
  final case class OnPacket[T <: Packet](packet: T, socket: ActorRef)
  final case class Subscribe[T <: OnData](endpoint: String, observer: Observer[T])(implicit val tag: TypeTag[T])
  final case class Broadcast(packet: Packet)

  // --- Observable data
  sealed trait OnData {
    def context: SocketIOContext
    implicit def endpoint: String

    def replyMessage(msg: String) = context.sendMessage(msg)
    def replyJson(json: JsValue) = context.sendJson(json)
    def replyEvent(name: String, args: List[JsValue]) = context.sendEvent(name, args)
    def reply(packet: Packet) = context.send(packet)
  }
  final case class OnConnect(args: Seq[(String, String)], context: SocketIOContext)(implicit val endpoint: String) extends OnData
  final case class OnMessage(msg: String, context: SocketIOContext)(implicit val endpoint: String) extends OnData
  final case class OnJson(json: JsValue, context: SocketIOContext)(implicit val endpoint: String) extends OnData
  final case class OnEvent(name: String, args: List[JsValue], context: SocketIOContext)(implicit val endpoint: String) extends OnData

  class Namespaces extends Actor with ActorLogging {
    import context.dispatcher

    def toName(endpoint: String) = if (endpoint == "") DEFAULT_NAMESPACE else endpoint

    def tryDispatch(namespace: String, msg: Any) {
      context.actorSelection(namespace).resolveOne(5.seconds).recover {
        case _: Throwable => context.actorOf(Props(classOf[Namespace], namespace), name = namespace)
      } map (_ ! msg)
    }

    def receive: Receive = {
      case x @ Subscribe(endpoint, observer) =>
        tryDispatch(toName(endpoint), x)

      case Session(sessionId) =>
        authorizedSessionIds += (
          sessionId -> Left(context.system.scheduler.scheduleOnce(socketio.closeTimeout.seconds) {
            authorizedSessionIds -= sessionId
          }))

      case x @ Connected(soContext: SocketIOContext) =>
        if (authorize(soContext)) {
          authorizedSessionIds(soContext.sessionId) = Right(soContext)
          context.watch(soContext.transportActor)
          soContext.withConnection(context.actorOf(Props(classOf[SocketIOConnection], soContext)))
          allConnections += (soContext.transportActor -> soContext)
        }

      case x @ OnPacket(packet @ ConnectPacket(endpoint, args), transportActor) =>
        allConnections.get(transportActor) foreach { ctx =>
          ctx.send(packet)
          tryDispatch(toName(endpoint), x)
        }

      case x @ OnPacket(packet @ DisconnectPacket(endpoint), transportActor) =>
        allConnections.get(transportActor) foreach { ctx =>
          authorizedSessionIds -= ctx.sessionId
        }
        allConnections -= transportActor
        context.actorSelection(toName(endpoint)) ! x

      case x @ OnPacket(HeartbeatPacket, transportActor) =>
        allConnections.get(transportActor) foreach { _.conn ! ReclockHeartbeatTimeout }

      case x @ OnPacket(packet, transportActor) =>
        context.actorSelection(toName(packet.endpoint)) ! x

      case Remove(namespace) =>
        val ns = context.actorSelection(namespace)
        ns ! Broadcast(DisconnectPacket(namespace))
        ns ! PoisonPill

      case x @ Broadcast(packet) =>
        context.actorSelection(toName(packet.endpoint)) ! x

      case Terminated(transportActor) =>
        allConnections.get(transportActor) foreach { ctx =>
          ctx.conn ! Pause
          authorizedSessionIds.get(ctx.sessionId) match {
            case Some(Right(_)) =>
              log.info("Will disconnect {} in {} seconds.", ctx.sessionId, socketio.closeTimeout)
              authorizedSessionIds(ctx.sessionId) = Left(context.system.scheduler.scheduleOnce(socketio.closeTimeout.seconds) {
                authorizedSessionIds -= ctx.sessionId
                context.stop(ctx.conn)
                log.info("Disconnected {}.", ctx.sessionId)
              })
            case _ =>
          }
        }
        log.info("Lost connection to: {}", transportActor)
        allConnections -= transportActor
    }
  }

}

/**
 * Namespace is refered to endpoint fo packets
 */
class Namespace(implicit val endpoint: String) extends Actor with ActorLogging {
  import Namespace._

  private val connections = new TrieMap[ActorRef, SocketIOContext]()

  val connectChannel = Subject[OnConnect]()
  val messageChannel = Subject[OnMessage]()
  val jsonChannel = Subject[OnJson]()
  val eventChannel = Subject[OnEvent]()

  def receive: Receive = {
    case OnPacket(packet: ConnectPacket, transportActor) =>
      context.watch(transportActor)
      allConnections.get(transportActor) foreach { ctx => connections += (transportActor -> ctx) }
      connections.get(transportActor) foreach { ctx => connectChannel.onNext(OnConnect(packet.args, ctx)) }
      log.info("clients for {}: {}", endpoint, connections)

    case OnPacket(packet: DisconnectPacket, transportActor) =>
      connections -= transportActor

    case OnPacket(packet: MessagePacket, transportActor) => connections.get(transportActor) foreach { ctx => messageChannel.onNext(OnMessage(packet.data, ctx)) }
    case OnPacket(packet: JsonPacket, transportActor)    => connections.get(transportActor) foreach { ctx => jsonChannel.onNext(OnJson(packet.json, ctx)) }
    case OnPacket(packet: EventPacket, transportActor)   => connections.get(transportActor) foreach { ctx => eventChannel.onNext(OnEvent(packet.name, packet.args, ctx)) }

    case x @ Subscribe(_, observer) =>
      x.tag.tpe match {
        case t if t =:= typeOf[OnConnect] => connectChannel(observer.asInstanceOf[Observer[OnConnect]])
        case t if t =:= typeOf[OnMessage] => messageChannel(observer.asInstanceOf[Observer[OnMessage]])
        case t if t =:= typeOf[OnJson]    => jsonChannel(observer.asInstanceOf[Observer[OnJson]])
        case t if t =:= typeOf[OnEvent]   => eventChannel(observer.asInstanceOf[Observer[OnEvent]])
        case _                            =>
      }

    case Broadcast(packet) =>
      gossip(packet)

    case Terminated(transportActor) =>
      connections -= transportActor
  }

  def gossip(packet: Packet) {
    connections foreach (_._2.send(packet))
  }

}
