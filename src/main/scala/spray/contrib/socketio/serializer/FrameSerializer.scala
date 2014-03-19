package spray.contrib.socketio.serializer

import akka.serialization.Serializer
import spray.contrib.socketio.packet.Packet
import spray.can.websocket.frame.{FrameParser, FrameRender, Frame}
import akka.util.ByteString
import akka.util.ByteIterator.ByteArrayIterator

class FrameSerializer extends Serializer {

   // This is whether "fromBinary" requires a "clazz" or not
   override def includeManifest: Boolean = true

   // Pick a unique identifier for your Serializer,
   // you've got a couple of billions to choose from,
   // 0 - 16 is reserved by Akka itself
   override def identifier: Int = 1000

   override def toBinary(o: AnyRef): Array[Byte] = {
     if (o.isInstanceOf[Frame]) {
       FrameRender(o.asInstanceOf[Frame]).toArray
     } else {
       Array[Byte]()
     }
   }

   override def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]): AnyRef = {
     var r: AnyRef = None
     new FrameParser().onReceive(ByteString(bytes).iterator) {
       case FrameParser.Success(frame) => r = frame
       case FrameParser.Failure(code, reason) => r = null
     }
     r
   }

 }
