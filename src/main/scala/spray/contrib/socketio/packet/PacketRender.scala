package spray.contrib.socketio.packet

import akka.util.ByteString
import akka.util.ByteStringBuilder
import scala.annotation.tailrec
import spray.json.JsValue

object PacketRender {

  def render(packet: Packet): ByteString = {
    val builder = ByteString.newBuilder

    builder.putByte(packet.code)
    builder.putByte(':')

    packet.id match {
      case -1 =>
      case id =>
        builder.putBytes(id.toString.getBytes)
        if (packet.isAckRequested) {
          builder.putByte('+')
        }
    }
    builder.putByte(':')

    packet.endpoint match {
      case "" =>
      case endpoint =>
        builder.putByte('/')
        builder.putBytes(endpoint.getBytes)
    }

    packet match {
      case x: MessagePacket ⇒
        if (x.data != "") {
          builder.putByte(':')
          builder.putBytes(x.data.getBytes)
        }

      case x: EventPacket ⇒
        builder.putByte(':')
        putJson(x.json, builder)

      case x: JsonPacket ⇒
        builder.putByte(':')
        putJson(x.json, builder)

      case ConnectPacket(_, args) ⇒
        if (args.nonEmpty) {
          builder.putByte('?')
          putArgs(args, builder)
        }

      case x: AckPacket ⇒
        if (x.ackId != -1 || x.args != "") {
          builder.putByte(':')
        }
        if (x.ackId != -1) {
          builder.putBytes(x.ackId.toString.getBytes)
        }
        if (x.args != "") {
          builder.putByte('+')
          builder.putBytes(x.args.getBytes)
          //jsonSupport.writeValue(out, packet.args)
        }

      case ErrorPacket(_, reason, advice) ⇒
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
      case _ =>
    }

    builder.result.compact
  }

  private def putJson(jsValue: JsValue, builder: ByteStringBuilder): ByteStringBuilder = {
    JsonRender(jsValue, builder)
  }

  @tailrec
  private def putArgs(args: Seq[(String, String)], builder: ByteStringBuilder): ByteStringBuilder = args match {
    case Seq((x, y), xs @ _*) =>
      builder.putBytes(x.getBytes).putByte('=').putBytes(y.getBytes)
      if (xs.nonEmpty) {
        builder.putByte('&')
      }
      putArgs(xs, builder)
    case _ => builder
  }

}
