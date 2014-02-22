package spray.contrib.socketio

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.Cancellable
import akka.actor.PoisonPill
import akka.actor.Props
import akka.actor.Terminated
import akka.io.Tcp
import rx.lang.scala.Observer
import rx.lang.scala.Subject
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration._
import scala.reflect.runtime.universe._
import spray.contrib.socketio
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
  val NAMESPACES = "socketio-namespaces"

  private val allConnections = new TrieMap[ActorRef, SocketIOContext]()
  private val authorizedSessionIds = new TrieMap[String, (Option[Cancellable], Option[SocketIOContext])]()
  def authorize(ctx: SocketIOContext): Boolean = {
    authorizedSessionIds.get(ctx.sessionId) match {
      case Some((Some(timeout), None)) =>
        true
      case Some((Some(timeout), Some(soContext))) => // a previous ctx existed, but is to be close by timeout
        true
      case Some((None, Some(soContext))) => // already occupied by socketio connection.
        false
      case Some((None, None)) => // should not happen
        false
      case None =>
        false
    }
  }

  def isSocketIOConnected(serverConnection: ActorRef) = allConnections.contains(serverConnection)

  final case class Remove(namespace: String)
  final case class Session(sessionId: String)
  final case class Connecting(soContext: SocketIOContext)
  final case class OnPacket[T <: Packet](packet: T, socket: ActorRef)
  final case class Subscribe[T <: OnData](endpoint: String, observer: Observer[T])(implicit val tag: TypeTag[T])
  final case class Broadcast(packet: Packet)

  final case class HeartbeatTimeout(transportActor: ActorRef)

  // --- Observable data
  sealed trait OnData {
    def context: SocketIOContext
    def endpoint: String

    def replyMessage(msg: String) = context.sendMessage(msg, endpoint)
    def replyJson(json: JsValue) = context.sendJson(json, endpoint)
    def replyEvent(name: String, args: JsValue*) = context.sendEvent(name, args.toList, endpoint)
    def reply(packets: Packet*) = context.send(packets.toList)
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
        authorizedSessionIds(sessionId) = (
          Some(context.system.scheduler.scheduleOnce(socketio.closeTimeout.seconds) {
            authorizedSessionIds -= sessionId
          }), None)

      case Connecting(soContext: SocketIOContext) =>
        if (authorize(soContext)) {
          authorizedSessionIds.get(soContext.sessionId) match {
            case Some((Some(timeout), None)) =>
              timeout.cancel
              soContext.withConnectionActive(context.actorOf(Props(classOf[ConnectionActive], self)))

            case Some((Some(timeout), Some(existedSoContext))) =>
              // a previous ctx existed, should use this one and attach the new transportActor to it, then resume connection
              timeout.cancel
              soContext.withConnectionActive(existedSoContext.connectionActive)

            case _ => // no authorized sessionId. Should not reach here
          }

          context.watch(soContext.serverConnection)
          authorizedSessionIds(soContext.sessionId) = (None, Some(soContext))
          allConnections(soContext.serverConnection) = soContext
        }

      case x @ OnPacket(packet @ ConnectPacket(endpoint, args), serverConnection) =>
        allConnections.get(serverConnection) foreach { ctx =>
          ctx.send(List(packet))
          tryDispatch(toName(endpoint), x)
        }

      case x @ OnPacket(packet @ DisconnectPacket(endpoint), serverConnection) =>
        allConnections.get(serverConnection) foreach { ctx =>
          authorizedSessionIds -= ctx.sessionId
        }
        allConnections -= serverConnection
        context.actorSelection(toName(endpoint)) ! x

      case x @ OnPacket(HeartbeatPacket, serverConnection) =>
        allConnections.get(serverConnection) foreach { _.connectionActive ! HeartbeatPacket }

      case x @ OnPacket(packet, serverConnection) =>
        context.actorSelection(toName(packet.endpoint)) ! x

      case Remove(namespace) =>
        val ns = context.actorSelection(namespace)
        ns ! Broadcast(DisconnectPacket(namespace))
        ns ! PoisonPill

      case x @ Broadcast(packet) =>
        context.actorSelection(toName(packet.endpoint)) ! x

      case Terminated(serverConnection)       => scheduleCloseConnection(serverConnection)
      case HeartbeatTimeout(serverConnection) => scheduleCloseConnection(serverConnection)
    }

    def scheduleCloseConnection(serverConnection: ActorRef) {
      allConnections.get(serverConnection) foreach { ctx =>
        authorizedSessionIds.get(ctx.sessionId) match {
          case Some((None, Some(soContext))) =>
            ctx.connectionActive ! ConnectionActive.Pause
            log.info("Will disconnect {} in {} seconds.", ctx.sessionId, socketio.closeTimeout)

            authorizedSessionIds(ctx.sessionId) = (
              Some(context.system.scheduler.scheduleOnce(socketio.closeTimeout.seconds) {
                authorizedSessionIds -= ctx.sessionId
                soContext.serverConnection ! Tcp.Close
                context.stop(ctx.connectionActive)
                log.info("Disconnected {}.", ctx.sessionId)
              }), Some(soContext))

          case Some((Some(timeout), _)) => // has been scheduled closing
          case _                        =>
        }
      }

      allConnections -= serverConnection
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
    case OnPacket(packet: ConnectPacket, serverConnection) =>
      context.watch(serverConnection)
      allConnections.get(serverConnection) foreach { ctx =>
        connections(serverConnection) = ctx
        connectChannel.onNext(OnConnect(packet.args, ctx))
      }

    case OnPacket(packet: DisconnectPacket, serverConnection) =>
      connections -= serverConnection

    case OnPacket(packet: MessagePacket, serverConnection) => connections.get(serverConnection) foreach { ctx => messageChannel.onNext(OnMessage(packet.data, ctx)) }
    case OnPacket(packet: JsonPacket, serverConnection)    => connections.get(serverConnection) foreach { ctx => jsonChannel.onNext(OnJson(packet.json, ctx)) }
    case OnPacket(packet: EventPacket, serverConnection)   => connections.get(serverConnection) foreach { ctx => eventChannel.onNext(OnEvent(packet.name, packet.args, ctx)) }

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

    case Terminated(serverConnection) =>
      connections -= serverConnection
  }

  def gossip(packet: Packet) {
    connections foreach (_._2.send(List(packet)))
  }

}
