package spray.contrib.socketio.packet

import akka.util.ByteString
import akka.util.ByteStringBuilder
import scala.annotation.tailrec
import spray.json._
import DefaultJsonProtocol._

object Packet {
  val reservedEvents = Set(
    "message",
    "connect",
    "disconnect",
    "open",
    "close",
    "error",
    "retry",
    "reconnect")
}

sealed trait Packet {
  def code: Byte
  def endpoint: String

  def render: ByteString

  override def toString = render.utf8String
}

/**
 * Signals disconnection. If no endpoint is specified, disconnects the entire socket.
 */
final case class DisconnectPacket(endpoint: String = "") extends Packet {
  def code = '0'

  def render = if (endpoint == "") ByteString(0) else ByteString("0::/" + endpoint)
}

/**
 * Only used for multiple sockets. Signals a connection to the endpoint. Once the
 * server receives it, it's echoed back to the client.
 */
final case class ConnectPacket(endpoint: String = "", args: Seq[(String, String)] = Nil) extends Packet {
  def code = '1'

  def render = args match {
    case Nil =>
      if (endpoint == "") ByteString("1::") else ByteString("1::/" + endpoint)
    case _ =>
      val builder = ByteString.newBuilder

      builder.putByte('1')
      builder.putByte(':')
      builder.putByte(':')
      if (endpoint != "") {
        builder.putByte('/')
        builder.putBytes(endpoint.getBytes)
      }
      builder.putByte('?')
      renderArgs(args, builder)

      builder.result.compact
  }

  @tailrec
  private def renderArgs(args: Seq[(String, String)], builder: ByteStringBuilder): ByteStringBuilder = args match {
    case Seq((x, y), xs @ _*) =>
      builder.putBytes(x.getBytes).putByte('=').putBytes(y.getBytes)
      if (xs.nonEmpty) {
        builder.putByte('&')
      }
      renderArgs(xs, builder)
    case _ => builder
  }
}

/**
 * Sends a heartbeat. Heartbeats must be sent within the interval negotiated with
 * the server. It's up to the client to decide the padding (for example, if the
 * heartbeat timeout negotiated with the server is 20s, the client might want to
 * send a heartbeat every 15s).
 */
case object HeartbeatPacket extends Packet {
  def code = '2'
  def endpoint = ""

  val render = ByteString("2::")
}

trait DataPacket extends Packet {
  def id: Long
  def isAckRequested: Boolean

  protected def renderHead = {
    val builder = ByteString.newBuilder

    builder.putByte(code)
    builder.putByte(':')

    id match {
      case -1 =>
      case id =>
        builder.putBytes(id.toString.getBytes)
        if (isAckRequested) {
          builder.putByte('+')
        }
    }
    builder.putByte(':')

    endpoint match {
      case "" =>
      case x =>
        builder.putByte('/')
        builder.putBytes(x.getBytes)
    }

    builder
  }
}

/**
 * A regular message.
 */
final case class MessagePacket(id: Long, isAckRequested: Boolean, endpoint: String, data: String) extends DataPacket {
  def code = '3'

  def render = {
    val builder = renderHead

    if (data != "") {
      builder.putByte(':')
      builder.putBytes(data.getBytes)
    }

    builder.result.compact
  }
}

/**
 * A JSON encoded message.
 */
final case class JsonPacket(id: Long, isAckRequested: Boolean, endpoint: String, json: JsValue) extends DataPacket {
  def code = '4'

  def render = {
    val builder = renderHead

    builder.putByte(':')
    JsonRender(json, builder)

    builder.result.compact
  }
}

/**
 * An event is like a json message, but has mandatory name and args fields. name
 * is a string and args an array.
 */
object EventPacket {
  def apply(id: Long, isAckRequested: Boolean, endpoint: String, json: JsValue): EventPacket = json match {
    case JsObject(fields) =>
      val name = fields.get("name") match {
        case Some(JsString(value)) => value
        case _                     => throw new Exception("Event packet is must have name field.")
      }
      val args = fields.get("args") match {
        case Some(JsArray(xs)) => xs
        case _                 => List()
      }
      apply(id, isAckRequested, endpoint, name, args)
    case _ => throw new Exception("Event packet is not a Json object.")
  }

  def apply(id: Long, isAckRequested: Boolean, endpoint: String, name: String, args: List[JsValue]): EventPacket =
    new EventPacket(id, isAckRequested, endpoint, name, args)

  def unapply(x: EventPacket): Option[(String, List[JsValue])] = Some(x.name, x.args)
}
final class EventPacket private (val id: Long, val isAckRequested: Boolean, val endpoint: String, val name: String, val args: List[JsValue]) extends DataPacket {
  def code = '5'
  def json = JsObject(Map("name" -> JsString(name), "args" -> JsArray(args)))

  def render = {
    val builder = renderHead

    builder.putByte(':')
    JsonRender(json, builder)

    builder.result.compact
  }
}

/**
 * An acknowledgment contains the message id as the message data. If a + sign
 * follows the message id, it's treated as an event message packet.
 * // TODO args
 */
final case class AckPacket(ackId: Long, args: String) extends Packet {
  def code = '6'
  def endpoint = ""

  def render = {
    val builder = ByteString.newBuilder

    builder.putByte('6')
    builder.putByte(':')
    builder.putByte(':')
    builder.putByte(':')

    if (ackId != -1 || args != "") {
      builder.putByte(':')
    }
    if (ackId != -1) {
      builder.putBytes(ackId.toString.getBytes)
    }
    if (args != "") {
      builder.putByte('+')
      builder.putBytes(args.getBytes)
      //jsonSupport.writeValue(out, packet.args)
    }

    builder.result.compact
  }
}

final case class ErrorPacket(endpoint: String, reason: String, advice: String = "") extends Packet {
  def code = '7'

  def render = {
    val builder = ByteString.newBuilder

    builder.putByte('7')
    builder.putByte(':')
    builder.putByte(':')
    if (endpoint != "") {
      builder.putByte('/')
      builder.putBytes(endpoint.getBytes)
    }

    if (reason != "" || advice != "") {
      builder.putByte(':')
    }
    if (reason != "") {
      builder.putBytes(reason.getBytes)
    }
    if (advice != "") {
      builder.putByte('+')
      builder.putBytes(advice.getBytes)
    }

    builder.result.compact
  }
}

/**
 * No operation. Used for example to close a poll after the polling duration times out.
 */
case object NoopPacket extends Packet {
  def code = '8'
  def endpoint = ""

  val render = ByteString('8'.toByte)
}
