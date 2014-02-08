package spray.contrib.socketio.packet

import akka.util.ByteString

object PacketRender {

  def render(packet: Packet): ByteString = {
    val builder = ByteString.newBuilder

    builder.putByte(packet.code)
    builder.putByte(Packet.SEPARATOR)

    packet.id match {
      case -1 =>
      case id =>
        builder.putBytes(id.toString.getBytes)
        if (packet.isAck) {
          builder.putByte(Packet.PLUS)
        }
    }
    builder.putByte(Packet.SEPARATOR)

    packet.endpoint match {
      case ""       =>
      case endpoint => builder.putBytes(endpoint.getBytes)
    }

    packet match {
      case x: MessagePacket ⇒
        if (x.data != "") {
          builder.putByte(Packet.SEPARATOR)
          builder.putBytes(x.data.getBytes)
        }

      case x: EventPacket ⇒
        builder.putByte(Packet.SEPARATOR)

      //val args = if (x.args.isEmpty) null else packet.args
      //jsonSupport.writeValue(out, Event(packet.name, args))

      case x: JsonMessagePacket ⇒
        builder.putByte(Packet.SEPARATOR)
      //jsonSupport.writeValue(out, packet.data)

      case ConnectPacket(_, args) if args != Nil ⇒
        builder.putByte(Packet.SEPARATOR)
      //builder.put(packet.qs.toString.getBytes)

      case x: AckPacket ⇒
        if (x.ackId != -1 || x.data != "") {
          builder.putByte(Packet.SEPARATOR)
        }
        if (x.ackId != -1) {
          builder.putBytes(x.ackId.toString.getBytes)
        }
        if (x.data != "") {
          builder.putByte(Packet.PLUS)
          builder.putBytes(x.data.getBytes)
          //jsonSupport.writeValue(out, packet.args)
        }

      case ErrorPacket(_, reason, advice) ⇒
        if (reason != "" || advice != "") {
          builder.putByte(Packet.SEPARATOR)
        }
        if (reason != "") {
          builder.putBytes(reason.getBytes)
        }
        if (advice != "") {
          builder.putByte(Packet.PLUS)
          builder.putBytes(advice.getBytes)
        }
      case _ =>
    }

    builder.result.compact
  }
}
