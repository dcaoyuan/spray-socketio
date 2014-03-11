package spray.contrib.socketio

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorSystem
import akka.actor.Props
import rx.lang.scala.Observer
import rx.lang.scala.Subject
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration._
import scala.reflect.runtime.universe._
import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success
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
import spray.json.JsValue

/**
 *
 *
 *   [client1 events] [client2 events]  ... [clientN events]
 *          |               |                      |
 *          |               |                      |
 *          V               V                      V
 *   +-----------------------------------------------------+
 *   |                    endpoint                         |
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
  val actorResolveTimeout = socketio.config.getInt("server.actor-selection-resolve-timeout")

  final case class RemoveNamespace(namespace: String)
  final case class Connecting(sessionId: String, query: Uri.Query, origins: Seq[HttpOrigin], transport: Transport)
  final case class OnPacket[T <: Packet](packet: T, connContext: ConnectionContext)
  final case class Subscribe[T <: OnData](system: ActorSystem, endpoint: String, observer: Observer[T])(implicit val tag: TypeTag[T])
  final case class Broadcast(packet: Packet)

  // --- Observable data
  sealed trait OnData {
    def context: ConnectionContext
    def endpoint: String

    def replyMessage(msg: String)(implicit selection: ConnectionActiveSelector) = selection.dispatch(ConnectionActive.SendMessage(context.sessionId, msg, endpoint))
    def replyJson(json: String)(implicit selection: ConnectionActiveSelector) = selection.dispatch(ConnectionActive.SendJson(context.sessionId, json, endpoint))
    def replyEvent(name: String, args: JsValue*)(implicit selection: ConnectionActiveSelector) = selection.dispatch(ConnectionActive.SendEvent(context.sessionId, name, args.toList, endpoint))
    def reply(packets: Packet*)(implicit selection: ConnectionActiveSelector) = selection.dispatch(ConnectionActive.SendPackets(context.sessionId, packets))
    def broadcast(packet: Packet)(implicit selection: ConnectionActiveSelector) {} //TODO
  }
  final case class OnConnect(args: Seq[(String, String)], context: ConnectionContext)(implicit val endpoint: String) extends OnData
  final case class OnDisconnect(context: ConnectionContext)(implicit val endpoint: String) extends OnData
  final case class OnMessage(msg: String, context: ConnectionContext)(implicit val endpoint: String) extends OnData
  final case class OnJson(json: JsValue, context: ConnectionContext)(implicit val endpoint: String) extends OnData
  final case class OnEvent(name: String, args: List[JsValue], context: ConnectionContext)(implicit val endpoint: String) extends OnData

  def subscribe[T <: OnData: TypeTag](endpoint: String, observer: Observer[T])(system: ActorSystem, props: Props) {
    tryDispatch(system, props, endpoint, Subscribe(system, endpoint, observer))
  }

  def namespaceFor(endpoint: String): String = if (endpoint == "") DEFAULT_NAMESPACE else endpoint

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

class GeneralNamespace(implicit val endpoint: String) extends Namespace

/**
 * Namespace is refered to endpoint fo packets
 */
trait Namespace extends Actor with ActorLogging {

  import Namespace._
  import context.dispatcher

  implicit def endpoint: String

  val connectChannel = Subject[OnConnect]()
  val disconnectChannel = Subject[OnDisconnect]()
  val messageChannel = Subject[OnMessage]()
  val jsonChannel = Subject[OnJson]()
  val eventChannel = Subject[OnEvent]()

  def noticeSubscribe(endpoint: String): Future[Any] = {
    Future.successful(true)
  }

  def receive: Receive = {
    case OnPacket(packet: ConnectPacket, connContext)    => connectChannel.onNext(OnConnect(packet.args, connContext))
    case OnPacket(packet: DisconnectPacket, connContext) => disconnectChannel.onNext(OnDisconnect(connContext))
    case OnPacket(packet: MessagePacket, connContext)    => messageChannel.onNext(OnMessage(packet.data, connContext))
    case OnPacket(packet: JsonPacket, connContext)       => jsonChannel.onNext(OnJson(packet.json, connContext))
    case OnPacket(packet: EventPacket, connContext)      => eventChannel.onNext(OnEvent(packet.name, packet.args, connContext))

    case x @ Subscribe(_, _, observer) =>
      noticeSubscribe(endpoint).onComplete {
        case Success(_) =>
          x.tag.tpe match {
            case t if t =:= typeOf[OnConnect]    => connectChannel(observer.asInstanceOf[Observer[OnConnect]])
            case t if t =:= typeOf[OnDisconnect] => disconnectChannel(observer.asInstanceOf[Observer[OnDisconnect]])
            case t if t =:= typeOf[OnMessage]    => messageChannel(observer.asInstanceOf[Observer[OnMessage]])
            case t if t =:= typeOf[OnJson]       => jsonChannel(observer.asInstanceOf[Observer[OnJson]])
            case t if t =:= typeOf[OnEvent]      => eventChannel(observer.asInstanceOf[Observer[OnEvent]])
            case _                               =>
          }
        case Failure(ex) =>
          log.warning("Failed on subscribe {}. due to {}", observer, ex.getMessage)
      }

    case Broadcast(packet) =>
      gossip(packet)

  }

  def gossip(packet: Packet) {
    //connections foreach (_._2.transport.sendPacket(packet))
  }

}
