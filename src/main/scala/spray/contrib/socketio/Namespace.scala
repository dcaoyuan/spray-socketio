package spray.contrib.socketio

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.Cancellable
import akka.actor.Props
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
import spray.contrib.socketio.transport.Transport
import spray.http.HttpOrigin
import spray.http.Uri
import spray.json.JsValue

/**
 *
 *
 *   [client1 events] [client2 events]  ... [clientN events]
 *          |               |                      |
 *          |               |                      |
 *          V               V                      V
 *   +-----------------------------------------------------+
 *   |                   namespaces                        |
 *   +-----------------------------------------------------+
 *               |                             |
 *               |                             |
 *               V                             V
 *         [namespace1]                  [namespace2]
 *               |
 *               |
 * (channel1)    +---> [************] -->
 * (channel2)    +---> [+++++++++] -->
 * (channelN)    +---> [$$$$$$$] -->
 *
 * @Note Akka can do millions of messages per second per actor per core.
 */
object Namespace {
  val DEFAULT_NAMESPACE = "socket.io"
  val NAMESPACES = "socketio-namespaces"

  final case class RemoveNamespace(namespace: String)
  final case class Session(sessionId: String)
  final case class Connecting(sessionId: String, query: Uri.Query, origins: Seq[HttpOrigin], transport: Transport)
  final case class AskConnectionContext(sessionId: String)
  final case class OnConnectPacket(packet: ConnectPacket, sessionId: String, connContext: ConnectionContext)
  final case class OnPacket[T <: Packet](packet: T, sessionId: String)
  final case class Subscribe[T <: OnData](endpoint: String, observer: Observer[T])(implicit val tag: TypeTag[T])
  final case class Broadcast(packet: Packet)
  final case class DropSessionId(sessionId: String, isClosing: Boolean)

  final case class SendHeartbeat(sessionId: String)
  final case class HeartbeatTimeout(sessionId: String)

  // --- Observable data
  sealed trait OnData {
    def context: ConnectionContext
    def endpoint: String

    def replyMessage(msg: String) = context.transport.sendMessage(msg, endpoint)
    def replyJson(json: JsValue) = context.transport.sendJson(json, endpoint)
    def replyEvent(name: String, args: JsValue*) = context.transport.sendEvent(name, args.toList, endpoint)
    def reply(packets: Packet*) = context.transport.sendPacket(packets: _*)
  }
  final case class OnConnect(args: Seq[(String, String)], context: ConnectionContext)(implicit val endpoint: String) extends OnData
  final case class OnMessage(msg: String, context: ConnectionContext)(implicit val endpoint: String) extends OnData
  final case class OnJson(json: JsValue, context: ConnectionContext)(implicit val endpoint: String) extends OnData
  final case class OnEvent(name: String, args: List[JsValue], context: ConnectionContext)(implicit val endpoint: String) extends OnData

  class Namespaces extends Actor with ActorLogging {
    import context.dispatcher

    private val authorizedSessionIds = new TrieMap[String, (Option[Cancellable], Option[ConnectionContext])]()

    def authorize(connecting: Connecting): Boolean = {
      authorizedSessionIds.get(connecting.sessionId) match {
        case Some((Some(timeout), None)) =>
          true
        case Some((Some(timeout), Some(connContext))) =>
          // a previous ctx existed, but is to be close by timeout
          true
        case Some((None, Some(connContext))) =>
          // already occupied by socketio connection.
          false
        case Some((None, None)) =>
          // should not happen
          false
        case None =>
          false
      }
    }

    def connectionContextFor(sessionId: String): Option[ConnectionContext] = authorizedSessionIds.get(sessionId).map(_._2).getOrElse(None)

    def tryDispatch(endpoint: String, msg: Any) {
      val namespace = if (endpoint == "") DEFAULT_NAMESPACE else endpoint
      context.actorSelection(namespace).resolveOne(5.seconds).recover {
        case _: Throwable => context.actorOf(Props(classOf[Namespace], namespace).withDispatcher(socketio.Settings.NamespaceDispatcher), name = namespace)
      } map (_ ! msg)
    }

    def dispatch(endpoint: String, msg: Any) {
      val namespace = if (endpoint == "") DEFAULT_NAMESPACE else endpoint
      context.actorSelection(namespace) ! msg
    }

    def receive: Receive = {
      case x @ Subscribe(endpoint, observer) =>
        tryDispatch(endpoint, x)

      case Session(sessionId) =>
        authorizedSessionIds(sessionId) = (
          Some(context.system.scheduler.scheduleOnce(
            socketio.Settings.CloseTimeout.seconds,
            self, DropSessionId(sessionId, isClosing = false))), None)

      case x @ Connecting(sessionId, query, origins, transport) =>
        if (authorize(x)) {
          val connContext = authorizedSessionIds.get(sessionId) match {
            case Some((Some(timeout), None)) =>
              timeout.cancel()
              val newContext = new ConnectionContext(sessionId, query, origins, self)
              newContext.bindTransport(transport)
              newContext.bindConnectionActive(context.actorOf(Props(classOf[ConnectionActive], newContext, self)))

            case Some((Some(timeout), Some(existedConnContext))) =>
              // a previous ctx existed, should use this one and attach the new transport to it, then resume connection
              timeout.cancel()
              existedConnContext.bindTransport(transport)
              existedConnContext

            case _ => throw new RuntimeException("no authorized sessionId. Should not reach here")
          }

          authorizedSessionIds(sessionId) = (None, Some(connContext))

          sender() ! Some(connContext)
        } else {
          sender() ! None
        }

      case AskConnectionContext(sessionId) =>
        sender() ! connectionContextFor(sessionId)

      case x @ OnPacket(packet @ ConnectPacket(endpoint, args), sessionId) =>
        connectionContextFor(sessionId) foreach { ctx =>
          // send connect handshake packet back to client
          ctx.transport.sendPacket(packet)
          tryDispatch(endpoint, OnConnectPacket(packet, sessionId, ctx))
        }

      case x @ OnPacket(packet @ DisconnectPacket(endpoint), sessionId) =>
        if (endpoint == "") {
          authorizedSessionIds -= sessionId
        }
        dispatch(endpoint, x)

      case x @ OnPacket(HeartbeatPacket, sessionId) =>
        connectionContextFor(sessionId) foreach { _.connectionActive ! HeartbeatPacket }

      case x @ OnPacket(packet, sessionId) =>
        log.debug("Got {}", x)
        dispatch(packet.endpoint, x)

      case RemoveNamespace(namespace) =>
        val ns = context.actorSelection(namespace)
        ns ! Broadcast(DisconnectPacket(namespace))
      //context.stop(ns)

      case x @ Broadcast(packet) =>
        dispatch(packet.endpoint, x)

      case SendHeartbeat(sessionId)    => connectionContextFor(sessionId) foreach { _.transport.sendPacket(HeartbeatPacket) }
      case HeartbeatTimeout(sessionId) => scheduleCloseConnection(sessionId)
      case DropSessionId(sessionId, isClosing) =>
        if (isClosing) {
          connectionContextFor(sessionId) foreach { connContext =>
            context.stop(connContext.connectionActive)
            //connContext.serverConnection ! Tcp.Close}
          }
        }
        authorizedSessionIds -= sessionId
        log.debug("{}: Disconnected.", sessionId)
    }

    def scheduleCloseConnection(sessionId: String) {
      authorizedSessionIds.get(sessionId) match {
        case Some((None, Some(connContext))) =>
          connContext.connectionActive ! ConnectionActive.Pause
          log.info("{} Will be disconnected in {} seconds.", sessionId, socketio.Settings.CloseTimeout)

          authorizedSessionIds(sessionId) = (
            Some(context.system.scheduler.scheduleOnce(
              socketio.Settings.CloseTimeout.seconds,
              self, DropSessionId(sessionId, isClosing = true))), Some(connContext))

        case Some((Some(timeout), _)) => // has been scheduled closing
        case _                        =>
      }
    }
  }

}

/**
 * Namespace is refered to endpoint fo packets
 */
class Namespace(implicit val endpoint: String) extends Actor with ActorLogging {
  import Namespace._

  private val connections = new TrieMap[String, ConnectionContext]()

  val connectChannel = Subject[OnConnect]()
  val messageChannel = Subject[OnMessage]()
  val jsonChannel = Subject[OnJson]()
  val eventChannel = Subject[OnEvent]()

  def receive: Receive = {
    case OnConnectPacket(packet, sessionId, ctx) =>
      connections(sessionId) = ctx
      connectChannel.onNext(OnConnect(packet.args, ctx))

    case OnPacket(packet: DisconnectPacket, sessionId) =>
      connections -= sessionId

    case OnPacket(packet: MessagePacket, sessionId) => connections.get(sessionId) foreach { ctx => messageChannel.onNext(OnMessage(packet.data, ctx)) }
    case OnPacket(packet: JsonPacket, sessionId)    => connections.get(sessionId) foreach { ctx => jsonChannel.onNext(OnJson(packet.json, ctx)) }
    case OnPacket(packet: EventPacket, sessionId)   => connections.get(sessionId) foreach { ctx => eventChannel.onNext(OnEvent(packet.name, packet.args, ctx)) }

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

  }

  def gossip(packet: Packet) {
    connections foreach (_._2.transport.sendPacket(packet))
  }

}
