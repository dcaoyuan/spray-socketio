package spray.contrib.socketio

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Props
import akka.actor.Terminated
import akka.pattern.ask
import rx.lang.scala.Observer
import rx.lang.scala.Subject
import scala.concurrent.duration._
import scala.reflect.runtime.universe._
import spray.contrib.socketio
import spray.contrib.socketio.packet.ConnectPacket
import spray.contrib.socketio.packet.DisconnectPacket
import spray.contrib.socketio.packet.EventPacket
import spray.contrib.socketio.packet.JsonPacket
import spray.contrib.socketio.packet.MessagePacket
import spray.contrib.socketio.packet.Packet
import spray.contrib.socketio.transport.Transport
import spray.http.HttpOrigin
import spray.http.Uri

/**
 *
 *
 *    +======node======+
 *    |       mediator----\
 *    |     /     |    |  |
 *    |    /      |    |  |
 *    | conn    conn   |  |
 *    | conn    conn   |  +---------------------------------------------------+
 *    |                |  |                  vitaul MEDIATOR                  |
 *    +================+  +---------------------------------------------------+
 *                        |       |
 *                        |       | (Namespace)
 *    +======node======+  |     +=|=============busi-node===============+
 *    |       mediator----/     | +endpointA              busi-actor(s) |
 *    |     /     |    |        |   |  |   |                            |
 *    |    /      |    |        |   |  |   +roomA                       |
 *    | conn    conn   |        |   |  |      |                         |
 *    | conn    conn   |        |   |  |      |                         |
 *    |                |        |   |  |      \---> channelA            |
 *    +================+        |   |  |      \---> channleB            |
 *                              |   |  |                                |
 *                              |   |  +roomB                           |
 *                              |   |     |                             |
 *                              |   |     |                             |
 *                              |   |     \---> channelA                |
 *                              |   |     \---> channelB                |
 *                              |   |                                   |
 *                              |   \---> channelA                      |
 *                              |   \---> channelB                      |
 *                              +=======================================+
 *
 *
 * @Note Akka can do millions of messages per second per actor per core.
 */
object Namespace {
  val DEFAULT_NAMESPACE = "socket.io"
  val NAMESPACES = "socketio-namespaces"
  val actorResolveTimeout = socketio.config.getInt("server.actor-selection-resolve-timeout")

  final case class RemoveNamespace(namespace: String)
  final case class Connecting(sessionId: String, query: Uri.Query, origins: Seq[HttpOrigin], transport: Transport)
  final case class OnPacket[T <: Packet](packet: T, connContext: ConnectionContext)
  final case class Subscribe[T <: OnData](observer: Observer[T])(implicit val tag: TypeTag[T])
  final case class SubscribeBroadcast(endpoint: String, ref: ActorRef)
  final case class UnsubscribeBroadcast(endpoint: String, ref: ActorRef)

  // --- Observable data
  sealed trait OnData {
    def context: ConnectionContext
    def endpoint: String

    def replyMessage(msg: String)(implicit resolver: ActorRef) = resolver ! ConnectionActive.SendMessage(context.sessionId, endpoint, msg)
    def replyJson(json: String)(implicit resolver: ActorRef) = resolver ! ConnectionActive.SendJson(context.sessionId, endpoint, json)
    def replyEvent(name: String, args: String)(implicit resolver: ActorRef) = resolver ! ConnectionActive.SendEvent(context.sessionId, endpoint, name, Left(args))
    def replyEvent(name: String, args: Seq[String])(implicit resolver: ActorRef) = resolver ! ConnectionActive.SendEvent(context.sessionId, endpoint, name, Right(args))
    def reply(packets: Packet*)(implicit resolver: ActorRef) = resolver ! ConnectionActive.SendPackets(context.sessionId, packets)
    def broadcast(packet: Packet)(implicit resolver: ActorRef) {} //TODO
  }
  final case class OnConnect(args: Seq[(String, String)], context: ConnectionContext)(implicit val endpoint: String) extends OnData
  final case class OnDisconnect(context: ConnectionContext)(implicit val endpoint: String) extends OnData
  final case class OnMessage(msg: String, context: ConnectionContext)(implicit val endpoint: String) extends OnData
  final case class OnJson(json: String, context: ConnectionContext)(implicit val endpoint: String) extends OnData
  final case class OnEvent(name: String, args: String, context: ConnectionContext)(implicit val endpoint: String) extends OnData

  def subscribe[T <: OnData: TypeTag](endpoint: String, observer: Observer[T])(system: ActorSystem, props: Props) {
    tryDispatch(system, props, endpoint, Subscribe(observer))
  }

  def subscribeBroadcast(endpoint: String, connectionActive: ActorRef)(system: ActorSystem) {
    dispatch(system, endpoint, SubscribeBroadcast(endpoint, connectionActive))
  }

  def unsubscribeBroadcast(endpoint: String, connectionActive: ActorRef)(system: ActorSystem) {
    dispatch(system, endpoint, UnsubscribeBroadcast(endpoint, connectionActive))
  }

  def namespaceFor(endpoint: String) = if (endpoint == "") DEFAULT_NAMESPACE else endpoint
  def endpointFor(namespace: String) = if (namespace == DEFAULT_NAMESPACE) "" else namespace
  def actorPath(namespace: String) = "/user/" + namespace

  def tryDispatch(system: ActorSystem, props: Props, endpoint: String, msg: Any) {
    val namespace = namespaceFor(endpoint)
    import system.dispatcher
    system.actorSelection(actorPath(namespace)).resolveOne(actorResolveTimeout.seconds).recover {
      case _: Throwable => system.actorOf(props, name = namespace)
    } map (_ ! msg)
  }

  def dispatch(system: ActorSystem, endpoint: String, msg: Any) {
    val namespace = namespaceFor(endpoint)
    import system.dispatcher
    system.actorSelection(actorPath(namespace)) ! msg
  }

}

class LocalNamespace(implicit val endpoint: String) extends Namespace

/**
 * Namespace is refered to endpoint fo packets
 */
trait Namespace extends Actor with ActorLogging {
  import Namespace._
  import context.dispatcher

  implicit def endpoint: String

  private var subsrcriptions = Set[ActorRef]()

  val connectChannel = Subject[OnConnect]()
  val disconnectChannel = Subject[OnDisconnect]()
  val messageChannel = Subject[OnMessage]()
  val jsonChannel = Subject[OnJson]()
  val eventChannel = Subject[OnEvent]()

  def noticeSubscribe(endpoint: String) {
    // override it if neccesary.
  }

  def receive: Receive = {
    case x @ Subscribe(observer) =>
      noticeSubscribe(endpoint)

      x.tag.tpe match {
        case t if t =:= typeOf[OnConnect]    => connectChannel(observer.asInstanceOf[Observer[OnConnect]])
        case t if t =:= typeOf[OnDisconnect] => disconnectChannel(observer.asInstanceOf[Observer[OnDisconnect]])
        case t if t =:= typeOf[OnMessage]    => messageChannel(observer.asInstanceOf[Observer[OnMessage]])
        case t if t =:= typeOf[OnJson]       => jsonChannel(observer.asInstanceOf[Observer[OnJson]])
        case t if t =:= typeOf[OnEvent]      => eventChannel(observer.asInstanceOf[Observer[OnEvent]])
        case _                               =>
      }

    case x @ SubscribeBroadcast(endpoint, ref) =>
      context.watch(ref)
      subsrcriptions += ref

    case x @ UnsubscribeBroadcast(endpoint, ref) =>
      context.unwatch(ref)
      subsrcriptions -= ref

    case OnPacket(packet: ConnectPacket, connContext) => connectChannel.onNext(OnConnect(packet.args, connContext))
    case OnPacket(packet: DisconnectPacket, connContext) => disconnectChannel.onNext(OnDisconnect(connContext))
    case OnPacket(packet: MessagePacket, connContext) => messageChannel.onNext(OnMessage(packet.data, connContext))
    case OnPacket(packet: JsonPacket, connContext) => jsonChannel.onNext(OnJson(packet.json, connContext))
    case OnPacket(packet: EventPacket, connContext) => eventChannel.onNext(OnEvent(packet.name, packet.args, connContext))

    case x @ ConnectionActive.OnBroadcast(endpoint, packet, sessionId) => subsrcriptions foreach (_ ! x)

    case Terminated(ref) => subsrcriptions -= ref
  }

}
