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

  def render = if (endpoint == "") ByteString('0') else ByteString("0::/" + endpoint)
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
  /**
   * The message id is an incremental integer, required for ACKs (can be omitted).
   * If the message id is followed by a +, the ACK is not handled by socket.io,
   * but by the user instead.
   */
  def id: Long
  def hasAckData: Boolean
  def isAckRequested = id != -1

  protected def renderPacketHead = {
    val builder = ByteString.newBuilder

    builder.putByte(code)
    builder.putByte(':')

    id match {
      case -1 =>
      case id =>
        builder.putBytes(id.toString.getBytes)
        if (hasAckData) {
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
final case class MessagePacket(id: Long, hasAckData: Boolean, endpoint: String, data: String) extends DataPacket {
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
final case class JsonPacket(id: Long, hasAckData: Boolean, endpoint: String, json: String) extends DataPacket {
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
  private val NamePart = "{\"name\":\"".getBytes
  private val ArgsPart = "\",\"args\":".getBytes
  private val EndsPart = "}".getBytes

  def splitNameArgs(json: String): (String, String) = {
    val nameStart = skip(json, 0, "{\"name\":\"", 0)
    if (nameStart != -1) {
      val nameEnd = eat(json, nameStart, '"')
      val name = json.substring(nameStart, nameEnd)
      val argsStart = skip(json, nameEnd, "\",\"args\":", 0)
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
      splitNameArgsBackward(json)
    }
  }

  def splitNameArgsBackward(json: String): (String, String) = {
    val nameEnd = skip(json, json.length - 1, "}\"", 0, forward = false)
    if (nameEnd != -1) {
      val nameStart = eat(json, nameEnd, '"', forward = false) // will stay at ix of "
      val name = json.substring(nameStart + 1, nameEnd + 1)
      val argsEnd = skip(json, nameStart - 1, ":\"eman\",", 0, forward = false)
      if (argsEnd != -1) {
        val argsStart = skip(json, 0, "{\"args\":", 0, forward = true)
        if (argsStart != -1) {
          val args = json.substring(argsStart, argsEnd + 1)
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
  private def skip(json: String, ix: Int, toSkip: String, count: Int, forward: Boolean = true): Int = {
    if (count == toSkip.length) {
      ix
    } else {
      val ix1 = skipWs(json, ix, forward)
      if (json(ix1) == toSkip(count)) {
        skip(json, if (forward) ix1 + 1 else ix1 - 1, toSkip, count + 1, forward)
      } else {
        -1
      }
    }
  }

  @tailrec
  private def skipWs(json: String, ix: Int, forward: Boolean = true): Int = {
    if (forward && ix < json.length || !forward && ix >= 0) {
      json(ix) match {
        case ' ' | '\t' => skipWs(json, if (forward) ix + 1 else ix - 1, forward)
        case _          => ix
      }
    } else {
      if (forward) 0 else json.length - 1
    }
  }

  private def eat(json: String, ix: Int, until: Char, forward: Boolean = true): Int = {
    var i = ix
    while ((forward && i < json.length || !forward && i >= 0) && json(i) != until) {
      if (forward) i += 1 else i -= 1
    }
    i
  }

  @tailrec
  private def stripEnds(json: String, ix: Int, until: Int, forward: Boolean = true): Int = {
    if (forward && ix > until || !forward && ix < until) {
      json(ix) match {
        case ' ' | '\t' => stripEnds(json, if (forward) ix - 1 else ix + 1, until, forward)
        case '}'        => ix
      }
    } else {
      -1
    }
  }

  /**
   * used by PacketParder only
   */
  private[packet] def apply(id: Long, hasAckData: Boolean, endpoint: String, json: String): EventPacket = {
    val (name, args) = splitNameArgs(json)
    new EventPacket(id, hasAckData, endpoint, name, args)
  }

  /**
   * Single full args json string. Should be enclosed with all args in one json array string and enlclosed with "[...]"
   */
  def apply(id: Long, hasAckData: Boolean, endpoint: String, name: String, args: String): EventPacket =
    new EventPacket(id, hasAckData, endpoint, name, args)

  /**
   * Seq of json string of each arg
   */
  def apply(id: Long, hasAckData: Boolean, endpoint: String, name: String, args: Seq[String]): EventPacket =
    new EventPacket(id, hasAckData, endpoint, name, args.mkString("[", ",", "]"))

  def unapply(x: EventPacket): Option[(String, String)] = Option(x.name, x.args)
}
final class EventPacket private (val id: Long, val hasAckData: Boolean, val endpoint: String, val name: String, val args: String) extends DataPacket {
  def code = '5'

  def render = {
    import EventPacket._
    val builder = renderPacketHead

    builder.putByte(':')
    builder.putBytes(NamePart)
    builder.putBytes(name.getBytes)
    builder.putBytes(ArgsPart)
    builder.putBytes(args.getBytes)
    builder.putBytes(EndsPart)

    builder.result.compact
  }

  override def equals(other: Any): Boolean = other match {
    case that: EventPacket =>
      id == that.id &&
        hasAckData == that.hasAckData &&
        endpoint == that.endpoint &&
        name == that.name &&
        args == that.args
    case _ => false
  }

  override def hashCode(): Int = {
    val state = Seq(id, hasAckData, endpoint, name, args)
    state.map(_.hashCode()).foldLeft(0)((a, b) => 31 * a + b)
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
