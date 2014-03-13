package spray.contrib.socketio.namespace

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Props
import akka.pattern.ask
import rx.lang.scala.Observer
import rx.lang.scala.Subject
import scala.concurrent.duration._
import scala.reflect.runtime.universe._
import spray.contrib.socketio
import spray.contrib.socketio.ConnectionActive
import spray.contrib.socketio.ConnectionContext
import spray.contrib.socketio.packet.ConnectPacket
import spray.contrib.socketio.packet.DisconnectPacket
import spray.contrib.socketio.packet.EventPacket
import spray.contrib.socketio.packet.JsonPacket
import spray.contrib.socketio.packet.MessagePacket
import spray.contrib.socketio.packet.Packet

/**
 *
 *
 *    +======node======+
 *    |       mediator----\
 *    |     /     |    |  |
 *    |    /      |    |  |
 *    | conn    conn   |  |
 *    | conn    conn   |  |---------------------------------------------------+
 *    |                |  |                  virtaul MEDIATOR                 |
 *    +================+  |---------------------------------------------------+
 *                        |       |
 *                        |       | (Namespace)
 *    +======node======+  |     +=V=============busi-node===============+
 *    |       mediator----/     | +endpointA              [busi-actors] |
 *    |     /     |    |        |   |  |   |                            |
 *    |    /      |    |        |   |  |   +roomA                       |
 *    | conn    conn   |        |   |  |      |                         |
 *    | conn    conn   |        |   |  |      |                         |
 *    | /              |        |   |  |      \---> channelA            |
 *    +=|==============+        |   |  |      \---> channleB            |
 *      |                       |   |  |                                |
 *      \                       |   |  +roomB                           |
 *       \                      |   |     |                             |
 *    +---|-------------+       |   |     |                             |
 *    |   | resolver    |       |   |     \---> channelA --> [observer]-----\
 *    +---|-------------+       |   |     \---> channelB                |   |
 *        |                     |   |                                   |   |
 *        |                     |   \---> channelA                      |   |
 *        |                     |   \---> channelB                      |   |
 *        |                     +=======================================+   |
 *        |                                                                 |
 *        |                                                                 |
 *        \-----------------------------------------------------------------/
 *
 *
 * @Note Akka can do millions of messages per second per actor per core.
 */
object Namespace {

  final case class Subscribe[T <: OnData](observer: Observer[T])(implicit val tag: TypeTag[T])

  // --- Observable data
  sealed trait OnData {
    def context: ConnectionContext
    def endpoint: String

    import ConnectionActive._
    def replyMessage(msg: String)(implicit resolver: ActorRef) = resolver ! SendMessage(context.sessionId, endpoint, msg)
    def replyJson(json: String)(implicit resolver: ActorRef) = resolver ! SendJson(context.sessionId, endpoint, json)
    def replyEvent(name: String, args: String)(implicit resolver: ActorRef) = resolver ! SendEvent(context.sessionId, endpoint, name, Left(args))
    def replyEvent(name: String, args: Seq[String])(implicit resolver: ActorRef) = resolver ! SendEvent(context.sessionId, endpoint, name, Right(args))
    def reply(packets: Packet*)(implicit resolver: ActorRef) = resolver ! SendPackets(context.sessionId, packets)
    def broadcast(topic: String, packet: Packet)(implicit resolver: ActorRef) = resolver ! Broadcast(context.sessionId, topic, packet)
  }
  final case class OnConnect(args: Seq[(String, String)], context: ConnectionContext)(implicit val endpoint: String) extends OnData
  final case class OnDisconnect(context: ConnectionContext)(implicit val endpoint: String) extends OnData
  final case class OnMessage(msg: String, context: ConnectionContext)(implicit val endpoint: String) extends OnData
  final case class OnJson(json: String, context: ConnectionContext)(implicit val endpoint: String) extends OnData
  final case class OnEvent(name: String, args: String, context: ConnectionContext)(implicit val endpoint: String) extends OnData

  def subscribe[T <: OnData: TypeTag](endpoint: String, observer: Observer[T])(system: ActorSystem, props: Props) {
    tryDispatch(system, props, endpoint, Subscribe(observer))
  }

  def actorPath(namespace: String) = "/user/" + namespace
  def tryDispatch(system: ActorSystem, props: Props, endpoint: String, msg: Any) {
    val namespace = socketio.namespaceFor(endpoint)
    import system.dispatcher
    system.actorSelection(actorPath(namespace)).resolveOne(socketio.actorResolveTimeout.seconds).recover {
      case _: Throwable => system.actorOf(props, name = namespace)
    } map (_ ! msg)
  }
}

/**
 * Namespace is refered to endpoint for observers
 */
trait Namespace extends Actor with ActorLogging {
  import Namespace._

  implicit def endpoint: String

  val connectChannel = Subject[OnConnect]()
  val disconnectChannel = Subject[OnDisconnect]()
  val messageChannel = Subject[OnMessage]()
  val jsonChannel = Subject[OnJson]()
  val eventChannel = Subject[OnEvent]()

  def subscribeMediator(endpoint: String)(action: () => Unit)

  def receive: Receive = {
    case x @ Subscribe(observer) =>
      subscribeMediator(endpoint) { () =>
        x.tag.tpe match {
          case t if t =:= typeOf[OnConnect]    => connectChannel(observer.asInstanceOf[Observer[OnConnect]])
          case t if t =:= typeOf[OnDisconnect] => disconnectChannel(observer.asInstanceOf[Observer[OnDisconnect]])
          case t if t =:= typeOf[OnMessage]    => messageChannel(observer.asInstanceOf[Observer[OnMessage]])
          case t if t =:= typeOf[OnJson]       => jsonChannel(observer.asInstanceOf[Observer[OnJson]])
          case t if t =:= typeOf[OnEvent]      => eventChannel(observer.asInstanceOf[Observer[OnEvent]])
          case _                               =>
        }
      }

    case ConnectionActive.OnPacket(packet: ConnectPacket, connContext)    => connectChannel.onNext(OnConnect(packet.args, connContext))
    case ConnectionActive.OnPacket(packet: DisconnectPacket, connContext) => disconnectChannel.onNext(OnDisconnect(connContext))
    case ConnectionActive.OnPacket(packet: MessagePacket, connContext)    => messageChannel.onNext(OnMessage(packet.data, connContext))
    case ConnectionActive.OnPacket(packet: JsonPacket, connContext)       => jsonChannel.onNext(OnJson(packet.json, connContext))
    case ConnectionActive.OnPacket(packet: EventPacket, connContext)      => eventChannel.onNext(OnEvent(packet.name, packet.args, connContext))
  }

}
