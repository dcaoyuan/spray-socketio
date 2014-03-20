package spray.contrib.socketio.serializer

import akka.serialization.{Serialization, Serializer}
import java.nio.ByteOrder
import spray.can.websocket.frame.{ FrameParser, FrameRender, TextFrame, Frame }
import akka.util.{ByteIterator, ByteStringBuilder, ByteString}
import spray.contrib.socketio.ConnectionActive._
import spray.contrib.socketio.ConnectionContext
import spray.contrib.socketio.packet.{DataPacket, Packet, PacketParser}
import spray.contrib.socketio.transport.Transport
import spray.contrib.socketio.ConnectionActive.OnPacket
import scala.util.Failure
import spray.contrib.socketio.ConnectionActive.OnFrame
import scala.util.Success
import spray.contrib.socketio.ConnectionActive.SendJson
import spray.contrib.socketio.ConnectionActive.SendMessage
import scala.collection.mutable.ListBuffer
import spray.http.{HttpOrigin, StringRendering}
import akka.actor.ExtendedActorSystem
import spray.http.Uri.Query

object StringSerializer {
  implicit val byteOrder = ByteOrder.BIG_ENDIAN

  def appendToBuilder(builder: ByteStringBuilder, str: String) {
    val bytes = str.getBytes
    builder.putInt(bytes.length)
    builder.putBytes(bytes)
  }

  def fromByteIterator(data: ByteIterator) = {
    val str = Array.ofDim[Byte](data.getInt)
    data.getBytes(str)
    new String(str)
  }
}

object FrameSerializer extends FrameSerializer
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

object PacketSerializer extends PacketSerializer
class PacketSerializer extends Serializer {
  implicit val byteOrder = ByteOrder.BIG_ENDIAN

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

  def appendToBuilder(builder: ByteStringBuilder, packet: Packet) {
    val bytes = toBinary(packet)
    builder.putInt(bytes.length)
    builder.putBytes(bytes)
  }

  def fromByteIterator(data: ByteIterator) = {
    val packetBytes = Array.ofDim[Byte](data.getInt)
    data.getBytes(packetBytes)
    PacketSerializer.fromBinary(packetBytes).asInstanceOf[Packet]
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

        StringSerializer.appendToBuilder(builder, x.sessionId)
        if (x.transport != null) {
          StringSerializer.appendToBuilder(builder, x.transport.ID)
        } else {
          StringSerializer.appendToBuilder(builder, "")
        }
        if (x.query != null) {
          StringSerializer.appendToBuilder(builder, x.query.render(new StringRendering).get)
        }
        x.origins.foreach {origin =>
          StringSerializer.appendToBuilder(builder, origin.render(new StringRendering).get)
        }

        builder.result.toArray
      case _ => Array[Byte]()
    }
  }

  override def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]): AnyRef = {
    val data = ByteString(bytes).iterator

    val sessionId = StringSerializer.fromByteIterator(data)
    val transId = StringSerializer.fromByteIterator(data)
    val query = Query.apply(StringSerializer.fromByteIterator(data))
    val origins = ListBuffer[HttpOrigin]()
    while(data.nonEmpty) {
      origins.append(HttpOrigin(StringSerializer.fromByteIterator(data)))
    }

    val ctx = new ConnectionContext(sessionId, query, origins)
    ctx.bindTransport(Transport.transportIds.getOrElse(transId, null))

    ctx
  }

  def appendToBuilder(builder: ByteStringBuilder, connctx: ConnectionContext) {
    val bytes = toBinary(connctx)
    builder.putInt(bytes.length)
    builder.putBytes(bytes)
  }

  def fromByteIterator(data: ByteIterator) = {
    val ctxBytes = Array.ofDim[Byte](data.getInt)
    data.getBytes(ctxBytes)
    ConnectionContextSerializer.fromBinary(ctxBytes).asInstanceOf[ConnectionContext]
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

        PacketSerializer.appendToBuilder(builder, packet)
        ConnectionContextSerializer.appendToBuilder(builder, connContext)

        builder.result.toArray
      case _ => Array[Byte]()
    }
  }

  override def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]): AnyRef = {
    val data = ByteString(bytes).iterator

    val packet = PacketSerializer.fromByteIterator(data)
    val ctx = ConnectionContextSerializer.fromByteIterator(data)

    OnPacket(packet, ctx)
  }
}

class CommandSerializer(val system: ExtendedActorSystem) extends Serializer {
  implicit val byteOrder = ByteOrder.BIG_ENDIAN

  override def includeManifest: Boolean = true

  override def identifier: Int = 2003

  private val fromBinaryMap = collection.immutable.HashMap[Class[_ <: Command], Array[Byte] => AnyRef](
    classOf[CreateSession] -> (bytes => fromBytesToCreateSession(bytes)),
    classOf[Connecting] -> (bytes => fromBytesToConnecting(bytes)),
    classOf[OnGet] -> (bytes => fromBytesToOnGet(bytes)),
    classOf[OnPost] -> (bytes => fromBytesToOnPost(bytes)),
    classOf[OnFrame] -> (bytes => fromBytesToOnFrame(bytes)),
    classOf[SendMessage] -> (bytes => fromBytesToSendMessage(bytes)),
    classOf[SendJson] -> (bytes => fromBytesToSendJson(bytes)),
    classOf[SendEvent] -> (bytes => fromBytesToSendEvent(bytes)),
    classOf[SendPackets] -> (bytes => fromBytesToSendPackets(bytes)),
    classOf[SendAck] -> (bytes => fromBytesToSendAck(bytes)),
    classOf[SubscribeBroadcast] -> (bytes => fromBytesToSubscribeBroadcast(bytes)),
    classOf[UnsubscribeBroadcast] -> (bytes => fromBytesToUnsubscribeBroadcast(bytes)),
    classOf[Broadcast] -> (bytes => fromBytesToBroadcast(bytes))
  )

  override def toBinary(o: AnyRef): Array[Byte] = {
    o match {
      case cmd: CreateSession => fromCreateSessionToBytes(cmd)
      case cmd: Connecting => fromConnectingToBytes(cmd)
      case cmd: OnGet => fromOnGetToBytes(cmd)
      case cmd: OnPost => fromOnPostToBytes(cmd)
      case cmd: OnFrame => fromOnFrameToBytes(cmd)
      case cmd: SendMessage => fromSendMessageToBytes(cmd)
      case cmd: SendJson => fromSendJsonToBytes(cmd)
      case cmd: SendEvent => fromSendEventToBytes(cmd)
      case cmd: SendPackets => fromSendPacketsToBytes(cmd)
      case cmd: SendAck => fromSendAckToBytes(cmd)
      case cmd: SubscribeBroadcast => fromSubscribeBroadcastToBytes(cmd)
      case cmd: UnsubscribeBroadcast => fromUnsubscribeBroadcastToBytes(cmd)
      case cmd: Broadcast => fromBroadcastToBytes(cmd)
    }
  }

  override def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]): AnyRef = {
    manifest match {
      case Some(clazz) => fromBinaryMap.get(clazz.asInstanceOf[Class[Command]]) match {
        case Some(f) => f(bytes)
        case None    => throw new IllegalArgumentException(s"Unimplemented deserialization of message class $clazz in CommandSerializer")
      }
      case _ => throw new IllegalArgumentException("Need a command message class to be able to deserialize bytes in CommandSerializer")
    }
  }

  def fromCreateSessionToBytes(cmd: CreateSession) = cmd.sessionId.getBytes

  def fromBytesToCreateSession(bytes: Array[Byte]) = CreateSession(new String(bytes))

  def fromConnectingToBytes(cmd: Connecting) = {
    val builder = ByteString.newBuilder

    StringSerializer.appendToBuilder(builder, cmd.sessionId)
    StringSerializer.appendToBuilder(builder, cmd.query.render(new StringRendering).get)
    if (cmd.transport != null) {
      StringSerializer.appendToBuilder(builder, cmd.transport.ID)
    } else {
      StringSerializer.appendToBuilder(builder, "")
    }
    StringSerializer.appendToBuilder(builder, Serialization.serializedActorPath(cmd.transportConnection))
    cmd.origins.foreach {origin =>
      StringSerializer.appendToBuilder(builder, origin.render(new StringRendering).get)
    }

    builder.result.toArray
  }

  def fromBytesToConnecting(bytes: Array[Byte]) = {
    val data = ByteString(bytes).iterator

    val sessionId = StringSerializer.fromByteIterator(data)
    val query = Query.apply(StringSerializer.fromByteIterator(data))
    val transport = Transport.transportIds.getOrElse(StringSerializer.fromByteIterator(data), null)
    val ref = system.actorFor(StringSerializer.fromByteIterator(data))
    val origins = ListBuffer[HttpOrigin]()
    while(data.nonEmpty) {
      origins.append(HttpOrigin(StringSerializer.fromByteIterator(data)))
    }
    Connecting(sessionId, query, origins, ref, transport)
  }

  def fromOnGetToBytes(cmd: OnGet) = {
    val builder = ByteString.newBuilder

    StringSerializer.appendToBuilder(builder, cmd.sessionId)
    StringSerializer.appendToBuilder(builder, Serialization.serializedActorPath(cmd.transportConnection))

    builder.result.toArray
  }

  def fromBytesToOnGet(bytes: Array[Byte]) = {
    val data = ByteString(bytes).iterator

    val sessionId = StringSerializer.fromByteIterator(data)
    val ref = system.actorFor(StringSerializer.fromByteIterator(data))

    OnGet(sessionId, ref)
  }

  def fromOnPostToBytes(cmd: OnPost) = {
    val builder = ByteString.newBuilder

    StringSerializer.appendToBuilder(builder, cmd.sessionId)
    StringSerializer.appendToBuilder(builder, Serialization.serializedActorPath(cmd.transportConnection))
    builder.append(cmd.payload)

    builder.result.toArray
  }

  def fromBytesToOnPost(bytes: Array[Byte]) = {
    val data = ByteString(bytes).iterator

    val sessionId = StringSerializer.fromByteIterator(data)
    val ref = system.actorFor(StringSerializer.fromByteIterator(data))
    val payload = data.toByteString

    OnPost(sessionId, ref, payload)
  }

  def fromOnFrameToBytes(cmd: OnFrame) = {
    val builder = ByteString.newBuilder

    StringSerializer.appendToBuilder(builder, cmd.sessionId)
    builder.append(FrameRender(cmd.frame))

    builder.result.toArray
  }

  def fromBytesToOnFrame(bytes: Array[Byte]) = {
    val data = ByteString(bytes).iterator

    val sessionId = StringSerializer.fromByteIterator(data)

    var r: OnFrame = null
    new FrameParser().onReceive(data) {
      case FrameParser.Success(frame: TextFrame) => r = OnFrame(sessionId, frame)
      case _                                     =>
    }
    r
  }

  def fromSendMessageToBytes(cmd: SendMessage) = {
    val builder = ByteString.newBuilder

    StringSerializer.appendToBuilder(builder, cmd.sessionId)
    StringSerializer.appendToBuilder(builder, cmd.endpoint)
    StringSerializer.appendToBuilder(builder, cmd.msg)

    builder.result.toArray
  }

  def fromBytesToSendMessage(bytes: Array[Byte]) = {
    val data = ByteString(bytes).iterator

    SendMessage(StringSerializer.fromByteIterator(data), StringSerializer.fromByteIterator(data), StringSerializer.fromByteIterator(data))
  }

  def fromSendJsonToBytes(cmd: SendJson) = {
    val builder = ByteString.newBuilder

    StringSerializer.appendToBuilder(builder, cmd.sessionId)
    StringSerializer.appendToBuilder(builder, cmd.endpoint)
    StringSerializer.appendToBuilder(builder, cmd.json)

    builder.result.toArray
  }

  def fromBytesToSendJson(bytes: Array[Byte]) = {
    val data = ByteString(bytes).iterator

    SendJson(StringSerializer.fromByteIterator(data), StringSerializer.fromByteIterator(data), StringSerializer.fromByteIterator(data))
  }

  def fromSendEventToBytes(cmd: SendEvent) = {
    val builder = ByteString.newBuilder

    StringSerializer.appendToBuilder(builder, cmd.sessionId)
    StringSerializer.appendToBuilder(builder, cmd.endpoint)
    StringSerializer.appendToBuilder(builder, cmd.name)

    cmd.args match {
      case Left(s) => StringSerializer.appendToBuilder(builder, s)
      case Right(ss) => ss.foreach(StringSerializer.appendToBuilder(builder, _))
    }

    builder.result.toArray
  }

  def fromBytesToSendEvent(bytes: Array[Byte]) = {
    val data = ByteString(bytes).iterator

    val sessionId = StringSerializer.fromByteIterator(data)
    val endpoint = StringSerializer.fromByteIterator(data)
    val name = StringSerializer.fromByteIterator(data)

    val ss = ListBuffer[String]()
    while(data.nonEmpty) {
      ss.append(StringSerializer.fromByteIterator(data))
    }
    val args: Either[String, Seq[String]] = ss.toList match {
      case (s: String) :: Nil => Left[String, Seq[String]](s)
      case l => Right[String, Seq[String]](l.toSeq)
    }
    SendEvent(sessionId, endpoint, name, args)
  }

  def fromSendPacketsToBytes(cmd: SendPackets) = {
    val builder = ByteString.newBuilder

    StringSerializer.appendToBuilder(builder, cmd.sessionId)
    cmd.packets.foreach (PacketSerializer.appendToBuilder(builder, _))

    builder.result.toArray
  }

  def fromBytesToSendPackets(bytes: Array[Byte]) = {
    val data = ByteString(bytes).iterator

    val sessionId = StringSerializer.fromByteIterator(data)
    val packets = ListBuffer[Packet]()
    while(data.nonEmpty) {
      packets.append(PacketSerializer.fromByteIterator(data))
    }
    SendPackets(sessionId, packets)
  }

  def fromSendAckToBytes(cmd: SendAck) = {
    val builder = ByteString.newBuilder

    StringSerializer.appendToBuilder(builder, cmd.sessionId)
    PacketSerializer.appendToBuilder(builder, cmd.originalPacket)
    StringSerializer.appendToBuilder(builder, cmd.args)

    builder.result.toArray
  }

  def fromBytesToSendAck(bytes: Array[Byte]) = {
    val data = ByteString(bytes).iterator

    val sessionId = StringSerializer.fromByteIterator(data)
    val packet = PacketSerializer.fromByteIterator(data).asInstanceOf[DataPacket]
    val args = StringSerializer.fromByteIterator(data)

    SendAck(sessionId, packet, args)
  }

  def fromSubscribeBroadcastToBytes(cmd: SubscribeBroadcast) = {
    val builder = ByteString.newBuilder

    StringSerializer.appendToBuilder(builder, cmd.sessionId)
    StringSerializer.appendToBuilder(builder, cmd.endpoint)
    StringSerializer.appendToBuilder(builder, cmd.room)

    builder.result.toArray
  }

  def fromBytesToSubscribeBroadcast(bytes: Array[Byte]) = {
    val data = ByteString(bytes).iterator

    SubscribeBroadcast(StringSerializer.fromByteIterator(data), StringSerializer.fromByteIterator(data), StringSerializer.fromByteIterator(data))
  }

  def fromUnsubscribeBroadcastToBytes(cmd: UnsubscribeBroadcast) = {
    val builder = ByteString.newBuilder

    StringSerializer.appendToBuilder(builder, cmd.sessionId)
    StringSerializer.appendToBuilder(builder, cmd.endpoint)
    StringSerializer.appendToBuilder(builder, cmd.room)

    builder.result.toArray
  }

  def fromBytesToUnsubscribeBroadcast(bytes: Array[Byte]) = {
    val data = ByteString(bytes).iterator

    UnsubscribeBroadcast(StringSerializer.fromByteIterator(data), StringSerializer.fromByteIterator(data), StringSerializer.fromByteIterator(data))

  }

  def fromBroadcastToBytes(cmd: Broadcast) = {
    val builder = ByteString.newBuilder

    StringSerializer.appendToBuilder(builder, cmd.sessionId)
    StringSerializer.appendToBuilder(builder, cmd.room)
    PacketSerializer.appendToBuilder(builder, cmd.packet)

    builder.result.toArray
  }

  def fromBytesToBroadcast(bytes: Array[Byte]) = {
    val data = ByteString(bytes).iterator

    val sessionId = StringSerializer.fromByteIterator(data)
    val room = StringSerializer.fromByteIterator(data)
    val packet = PacketSerializer.fromByteIterator(data)

    Broadcast(sessionId, room, packet)
  }

}