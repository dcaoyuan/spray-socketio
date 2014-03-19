package spray.contrib.socketio.serializer

import akka.serialization.Serializer
import spray.contrib.socketio.packet.{PacketParser, Packet}
import akka.util.ByteString

class PacketSerializer extends Serializer {

  // This is whether "fromBinary" requires a "clazz" or not
  override def includeManifest: Boolean = true

  // Pick a unique identifier for your Serializer,
  // you've got a couple of billions to choose from,
  // 0 - 16 is reserved by Akka itself
  override def identifier: Int = 2000

  override def toBinary(o: AnyRef): Array[Byte] = {
    if (o.isInstanceOf[Packet]) {
       o.asInstanceOf[Packet].render.toArray
    } else {
      Array[Byte]()
    }
  }

  override def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]): AnyRef = {
    val seq = PacketParser.apply(ByteString(bytes))
    if (seq.isSuccess && seq.get.nonEmpty) seq.get(0)
    else null
  }

}
