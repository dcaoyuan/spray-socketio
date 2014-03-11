package spray.contrib.socketio.packet

import akka.util.ByteString
import akka.util.ByteStringBuilder
import scala.annotation.tailrec

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

  def renderToString(packets: List[Packet]): String = {
    packets match {
      case Nil      => ""
      case h :: Nil => h.render.utf8String
      case xs       => renderToString(new StringBuilder(), xs)
    }
  }

  @tailrec
  private def renderToString(builder: StringBuilder, packets: List[Packet]): String = {
    packets match {
      case Nil => builder.toString
      case h :: xs =>
        val msg = h.render.utf8String
        builder.append('\ufffd').append(msg.length.toString).append('\ufffd').append(msg)
        renderToString(builder, xs)
    }
  }
}

sealed trait Packet extends Serializable {
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

  val utf8String = "2::"
  val render = ByteString(utf8String)
}

trait DataPacket extends Packet {
  def id: Long
  def isAckRequested: Boolean

  protected def renderPacketHead = {
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
    val builder = renderPacketHead

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
final case class JsonPacket(id: Long, isAckRequested: Boolean, endpoint: String, json: String) extends DataPacket {
  def code = '4'

  def render = {
    val builder = renderPacketHead

    builder.putByte(':')
    builder.putBytes(json.getBytes)

    builder.result.compact
  }
}

/**
 * An event is like a json message, but has mandatory name and args fields. name
 * is a string and args an array.
 */
object EventPacket {
  private val nameHeading = "{\"name\":\""
  private val argsHeading = "\",\"args\":"

  private val namePart = nameHeading.getBytes
  private val argsPart = argsHeading.getBytes
  private val endsPart = "}".getBytes

  def splitNameArgs(json: String): (String, String) = {
    val nameStart = skip(json, 0, nameHeading, 0)
    if (nameStart != -1) {
      val nameEnd = eat(json, nameStart, '"')
      val name = json.substring(nameStart, nameEnd)
      val argsStart = skip(json, nameEnd, argsHeading, 0)
      if (argsStart != -1) {
        val argsEnd = stripEnds(json, json.length - 1, argsStart)
        if (argsEnd != -1) {
          val args = json.substring(argsStart, argsEnd)
          (name, args)
        } else {
          (name, "[]")
        }
      } else {
        (name, "[]")
      }
    } else {
      ("", "[]")
    }
  }

  @tailrec
  private def skip(json: String, ix: Int, toSkip: String, skipIx: Int): Int = {
    if (skipIx == toSkip.length) {
      ix
    } else {
      val ix1 = skipWs(json, ix)
      if (json(ix1) == toSkip(skipIx)) {
        skip(json, ix1 + 1, toSkip, skipIx + 1)
      } else {
        -1
      }
    }
  }

  @tailrec
  private def skipWs(json: String, ix: Int): Int = {
    if (ix < json.length) {
      json(ix) match {
        case ' ' | '\t' => skipWs(json, ix + 1)
        case _          => ix
      }
    } else {
      json.length - 1
    }
  }

  private def eat(json: String, ix: Int, until: Char): Int = {
    var i = ix
    while (i < json.length && json(i) != until) {
      i += 1
    }
    i
  }

  @tailrec
  private def stripEnds(json: String, ix: Int, until: Int): Int = {
    if (ix > until) {
      json(ix) match {
        case ' ' | '\t' => stripEnds(json, ix - 1, until)
        case '}'        => ix
      }
    } else {
      -1
    }
  }

  /**
   * used by PacketParder only
   */
  private[packet] def apply(id: Long, isAckRequested: Boolean, endpoint: String, json: String): EventPacket = {
    val (name, args) = splitNameArgs(json)
    new EventPacket(id, isAckRequested, endpoint, name, args)
  }

  def apply(id: Long, isAckRequested: Boolean, endpoint: String, name: String, args: String): EventPacket =
    new EventPacket(id, isAckRequested, endpoint, name, args)

  def unapply(x: EventPacket): Option[(String, String)] = Option(x.name, x.args)
}
final class EventPacket private (val id: Long, val isAckRequested: Boolean, val endpoint: String, val name: String, val args: String) extends DataPacket {
  def code = '5'

  def render = {
    import EventPacket._
    val builder = renderPacketHead

    builder.putByte(':')
    builder.putBytes(namePart)
    builder.putBytes(name.getBytes)
    builder.putBytes(argsPart)
    builder.putBytes(args.getBytes)
    builder.putBytes(endsPart)

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

  val utf8String = "8::"
  val render = ByteString(utf8String)
}
