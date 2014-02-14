package spray.contrib.socketio.namespace

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.PoisonPill
import akka.actor.Props
import rx.lang.scala.Observer
import rx.lang.scala.Subject
import scala.collection.mutable
import scala.concurrent.duration._
import scala.reflect.runtime.universe._
import scala.util.Failure
import scala.util.Success
import spray.contrib.socketio.packet.ConnectPacket
import spray.contrib.socketio.packet.DisconnectPacket
import spray.contrib.socketio.packet.EventPacket
import spray.contrib.socketio.packet.JsonMessagePacket
import spray.contrib.socketio.packet.MessagePacket
import spray.contrib.socketio.packet.Packet

object Namespace {
  val DEFAULT_NAMESPACE = "socket.io"

  final case class Remove(name: String)
  final case class OnPacket[T <: Packet](packet: T, client: ActorRef)
  final case class Subscribe[T <: Packet](endpoint: String, observer: Observer[OnPacket[T]])(implicit val tag: TypeTag[T])

  class Namespaces extends Actor {
    import context.dispatcher

    private def toName(endpoint: String) = if (endpoint == "") DEFAULT_NAMESPACE else endpoint

    def receive: Receive = {
      case x @ Subscribe(endpoint, observer) =>
        val name = toName(endpoint)
        context.actorSelection(name).resolveOne(5.seconds).onComplete {
          case Success(a) => a ! x
          case Failure(_) => context.actorOf(Props(classOf[Namespace], name), name = name) ! x
        }

      case x @ OnPacket(ConnectPacket(endpoint, args), client) =>
        val name = toName(endpoint)
        context.actorSelection(name).resolveOne(5.seconds).onComplete {
          case Success(_) =>
          case Failure(_) => context.actorOf(Props(classOf[Namespace], name), name = name)
        }

      case x @ OnPacket(packet, client) =>
        val name = toName(packet.endpoint)
        context.actorSelection(name) ! x

      case Remove(name) =>
        val ns = context.actorSelection(name)
        ns ! "broadcastOperations.disconnect()"
        ns ! PoisonPill

    }
  }

}

/**
 * name of Namespace is refered to endpoint fo packets
 */
class Namespace private (val name: String) extends Actor with ActorLogging {
  import Namespace._

  val connectChannel = Subject[OnPacket[ConnectPacket]]()
  val eventChannel = Subject[OnPacket[EventPacket]]()
  val messageChannel = Subject[OnPacket[MessagePacket]]()
  val jsonChannel = Subject[OnPacket[JsonMessagePacket]]()

  def receive: Receive = {
    case OnPacket(packet: ConnectPacket, client)     => connectChannel.onNext(OnPacket(packet, client))
    case OnPacket(packet: EventPacket, client)       => eventChannel.onNext(OnPacket(packet, client))
    case OnPacket(packet: MessagePacket, client)     => messageChannel.onNext(OnPacket(packet, client))
    case OnPacket(packet: JsonMessagePacket, client) => jsonChannel.onNext(OnPacket(packet, client))
    case OnPacket(packet: DisconnectPacket, client)  => connectChannel.onCompleted
    case x @ Subscribe(_, observer) =>
      x.tag.tpe match {
        case t if t =:= typeOf[ConnectPacket]     => connectChannel(observer.asInstanceOf[Observer[OnPacket[ConnectPacket]]])
        case t if t =:= typeOf[EventPacket]       => eventChannel(observer.asInstanceOf[Observer[OnPacket[EventPacket]]])
        case t if t =:= typeOf[MessagePacket]     => messageChannel(observer.asInstanceOf[Observer[OnPacket[MessagePacket]]])
        case t if t =:= typeOf[JsonMessagePacket] => jsonChannel(observer.asInstanceOf[Observer[OnPacket[JsonMessagePacket]]])
        case t if t =:= typeOf[DisconnectPacket]  => //
        case _                                    =>
      }
  }

}
