package spray.contrib.socketio.packet

import akka.util.ByteString

object PacketRender {
  val PLUS = '+'.toByte
  val SLASH = '/'.toByte
  val SEPARATOR = ':'.toByte

  def render(packet: Packet): ByteString = {
    val builder = ByteString.newBuilder

    builder.putByte(packet.code)
    builder.putByte(SEPARATOR)

    packet.id match {
      case -1 =>
      case id =>
        builder.putBytes(id.toString.getBytes)
        if (packet.isAckRequested) {
          builder.putByte(PLUS)
        }
    }
    builder.putByte(SEPARATOR)

    packet.endpoint match {
      case "" =>
      case endpoint =>
        builder.putByte(SLASH)
        builder.putBytes(endpoint.getBytes)
    }

    packet match {
      case x: MessagePacket ⇒
        if (x.data != "") {
          builder.putByte(SEPARATOR)
          builder.putBytes(x.data.getBytes)
        }

      case x: EventPacket ⇒
        builder.putByte(SEPARATOR)

      //val args = if (x.args.isEmpty) null else packet.args
      //jsonSupport.writeValue(out, Event(packet.name, args))

      case x: JsonPacket ⇒
        builder.putByte(SEPARATOR)
      //jsonSupport.writeValue(out, packet.data)

      case ConnectPacket(_, args) if args != Nil ⇒
        builder.putByte(SEPARATOR)
      //builder.put(packet.qs.toString.getBytes)

      case x: AckPacket ⇒
        if (x.ackId != -1 || x.args != "") {
          builder.putByte(SEPARATOR)
        }
        if (x.ackId != -1) {
          builder.putBytes(x.ackId.toString.getBytes)
        }
        if (x.args != "") {
          builder.putByte(PLUS)
          builder.putBytes(x.args.getBytes)
          //jsonSupport.writeValue(out, packet.args)
        }

      case ErrorPacket(_, reason, advice) ⇒
        if (reason != "" || advice != "") {
          builder.putByte(SEPARATOR)
        }
        if (reason != "") {
          builder.putBytes(reason.getBytes)
        }
        if (advice != "") {
          builder.putByte(PLUS)
          builder.putBytes(advice.getBytes)
        }
      case _ =>
    }

    builder.result.compact
  }
}
