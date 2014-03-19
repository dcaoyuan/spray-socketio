package spray.contrib.socketio.serializer

import akka.serialization.Serializer
import java.nio.ByteOrder
import scala.util.Failure
import scala.util.Success
import spray.can.websocket.frame.{ FrameParser, FrameRender, TextFrame, Frame }
import akka.util.ByteString
import spray.contrib.socketio.ConnectionActive.OnFrame
import spray.contrib.socketio.ConnectionActive.OnPacket
import spray.contrib.socketio.ConnectionContext
import spray.contrib.socketio.packet.Packet
import spray.contrib.socketio.packet.PacketParser
import spray.contrib.socketio.transport.Transport

class FrameSerializer extends Serializer {

  // This is whether "fromBinary" requires a "clazz" or not
  override def includeManifest: Boolean = false

  // Pick a unique identifier for your Serializer,
  // you've got a couple of billions to choose from,
  // 0 - 16 is reserved by Akka itself
  override def identifier: Int = 1000

  override def toBinary(o: AnyRef): Array[Byte] = {
    o match {
      case x: Frame => FrameRender(x).toArray
      case _        => Array[Byte]()
    }
  }

  override def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]): AnyRef = {
    var r: Frame = null
    new FrameParser().onReceive(ByteString(bytes).iterator) {
      case FrameParser.Success(frame) => r = frame
      case FrameParser.Failure(_, _)  =>
    }
    r
  }
}

class OnFrameSerializer extends Serializer {
  implicit val byteOrder = ByteOrder.BIG_ENDIAN

  override def includeManifest: Boolean = false

  override def identifier: Int = 1001

  override def toBinary(o: AnyRef): Array[Byte] = {
    o match {
      case OnFrame(sessionId, frame) =>
        val builder = ByteString.newBuilder

        val bytes1 = sessionId.getBytes
        builder.putInt(bytes1.length)
        builder.putBytes(bytes1)

        builder.append(FrameRender(frame))

        builder.result.toArray
      case _ => Array[Byte]()
  }
    }

  override def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]): AnyRef = {
    val data = ByteString(bytes).iterator

    val sessionId = Array.ofDim[Byte](data.getInt)
    data.getBytes(sessionId)

    var r: OnFrame = null
    new FrameParser().onReceive(data) {
      case FrameParser.Success(frame: TextFrame) => r = OnFrame(new String(sessionId), frame)
      case _                                     =>
    }
    r
  }
}

object PacketSerializer extends PacketSerializer
class PacketSerializer extends Serializer {

  override def includeManifest: Boolean = false

  override def identifier: Int = 2000

  override def toBinary(o: AnyRef): Array[Byte] = {
    o match {
      case x: Packet => x.render.toArray
      case _         => Array[Byte]()
    }
  }

  override def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]): AnyRef = {
    PacketParser.apply(ByteString(bytes)) match {
      case Success(Seq(packet)) => packet
      case Failure(ex)          => null
    }
  }
}

// ConnectionContext(val sessionId: String, val query: Uri.Query, val origins: Seq[HttpOrigin])
object ConnectionContextSerializer extends ConnectionContextSerializer
class ConnectionContextSerializer extends Serializer {
  implicit val byteOrder = ByteOrder.BIG_ENDIAN

  override def includeManifest: Boolean = false

  override def identifier: Int = 2001

  override def toBinary(o: AnyRef): Array[Byte] = {
    o match {
      case x: ConnectionContext =>
        val builder = ByteString.newBuilder

        val bytes1 = x.sessionId.getBytes
        builder.putInt(bytes1.length)
        builder.putBytes(bytes1)
        // TODO query, origins etc

        if (x.transport != null) {
          val bytes2 = x.transport.ID.getBytes
          builder.putInt(bytes2.length)
          builder.putBytes(bytes2)
        }

        builder.result.toArray
      case _ => Array[Byte]()
    }
  }

  override def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]): AnyRef = {
    val data = ByteString(bytes).iterator

    val sessionId = Array.ofDim[Byte](data.getInt)
    data.getBytes(sessionId)

    val ctx = new ConnectionContext(new String(sessionId), null, List())

    if (data.len > 0) {
      val transId = Array.ofDim[Byte](data.getInt)
      data.getBytes(transId)
      ctx.bindTransport(Transport.transportIds(new String(transId)))
    }

    ctx
  }
}

class OnPacketSerializer extends Serializer {
  implicit val byteOrder = ByteOrder.BIG_ENDIAN

  override def includeManifest: Boolean = false

  override def identifier: Int = 2002

  override def toBinary(o: AnyRef): Array[Byte] = {
    o match {
      case OnPacket(packet: Packet, connContext: ConnectionContext) =>
        val builder = ByteString.newBuilder

        val bytes1 = PacketSerializer.toBinary(packet)
        builder.putInt(bytes1.length)
        builder.putBytes(bytes1)

        val bytes2 = ConnectionContextSerializer.toBinary(connContext)
        builder.putInt(bytes2.length)
        builder.putBytes(bytes2)

        builder.result.toArray
      case _ => Array[Byte]()
    }
  }

  override def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]): AnyRef = {
    val data = ByteString(bytes).iterator

    val packetBytes = Array.ofDim[Byte](data.getInt)
    data.getBytes(packetBytes)
    val packet = PacketSerializer.fromBinary(packetBytes).asInstanceOf[Packet]

    val ctxBytes = Array.ofDim[Byte](data.getInt)
    data.getBytes(ctxBytes)
    val ctx = ConnectionContextSerializer.fromBinary(ctxBytes).asInstanceOf[ConnectionContext]

    OnPacket(packet, ctx)
  }
}