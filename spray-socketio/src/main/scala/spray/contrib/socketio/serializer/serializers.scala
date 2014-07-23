package spray.contrib.socketio.serializer

import akka.actor.ExtendedActorSystem
import akka.serialization.{ Serialization, Serializer }
import akka.util.{ ByteIterator, ByteStringBuilder, ByteString }
import java.nio.ByteOrder
import spray.can.websocket.frame.{ FrameParser, FrameRender, Frame }
import spray.contrib.socketio
import spray.contrib.socketio.ConnectionActive
import spray.contrib.socketio.ConnectionContext
import spray.contrib.socketio.packet.{ DataPacket, Packet, PacketParser }
import spray.contrib.socketio.transport
import spray.contrib.socketio.transport.Transport
import spray.http.{ HttpOrigin, StringRendering }
import spray.http.Uri.Query
import scala.collection.mutable
import scala.collection.immutable
import scala.util.Failure
import scala.util.Success

object StringSerializer {
  implicit val byteOrder = ByteOrder.BIG_ENDIAN

  def appendToBuilder(builder: ByteStringBuilder, str: String) {
    if (str != null) {
      val bytes = str.getBytes
      builder.putInt(bytes.length)
      builder.putBytes(bytes)
    } else {
      builder.putInt(-1)
    }
  }

  def fromByteIterator(data: ByteIterator): String = {
    val len = data.getInt
    if (len >= 0) {
      val str = Array.ofDim[Byte](len)
      data.getBytes(str)
      new String(str)
    } else {
      null
    }
  }
}

object FrameSerializer extends FrameSerializer
class FrameSerializer extends Serializer {

  // This is whether "fromBinary" requires a "clazz" or not
  final def includeManifest: Boolean = false

  // Pick a unique identifier for your Serializer,
  // you've got a couple of billions to choose from,
  // 0 - 16 is reserved by Akka itself
  final def identifier: Int = 1000

  final def toBinary(o: AnyRef): Array[Byte] = {
    o match {
      case x: Frame => FrameRender(x).toArray
      case _        => Array[Byte]()
    }
  }

  final def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]): AnyRef = {
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

  final def includeManifest: Boolean = false

  final def identifier: Int = 2000

  final def toBinary(o: AnyRef): Array[Byte] = {
    o match {
      case x: Packet => x.render.toArray
      case _         => Array[Byte]()
    }
  }

  final def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]): AnyRef = {
    PacketParser.apply(ByteString(bytes)) match {
      case Success(Seq(packet)) => packet
      case Failure(ex)          => null
    }
  }

  final def appendToBuilder(builder: ByteStringBuilder, packet: Packet) {
    val bytes = toBinary(packet)
    builder.putInt(bytes.length)
    builder.putBytes(bytes)
  }

  final def fromByteIterator(data: ByteIterator) = {
    val packetBytes = Array.ofDim[Byte](data.getInt)
    data.getBytes(packetBytes)
    PacketSerializer.fromBinary(packetBytes).asInstanceOf[Packet]
  }
}

class ConnectionActiveStateSerializer(val system: ExtendedActorSystem) extends Serializer {
  implicit val byteOrder = ByteOrder.BIG_ENDIAN

  final def includeManifest: Boolean = false

  final def identifier: Int = 2008

  final def toBinary(o: AnyRef): Array[Byte] = {
    o match {
      case x: ConnectionActive.State =>
        val builder = ByteString.newBuilder

        ConnectionContextSerializer.appendToBuilder(builder, x.context)
        StringSerializer.appendToBuilder(builder, Serialization.serializedActorPath(x.transportConnection))
        x.topics foreach { StringSerializer.appendToBuilder(builder, _) }

        builder.result.toArray

      case _ => Array[Byte]()
    }
  }

  final def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]): AnyRef = {
    val data = ByteString(bytes).iterator

    val ctx = ConnectionContextSerializer.fromByteIterator(data)
    val transportConn = system.provider.resolveActorRef(StringSerializer.fromByteIterator(data))

    var topics = immutable.Set[String]()
    while (data.nonEmpty) {
      topics += StringSerializer.fromByteIterator(data)
    }

    new ConnectionActive.State(ctx, transportConn, topics)
  }
}

object ConnectionContextSerializer extends ConnectionContextSerializer {
  def appendToBuilder(builder: ByteStringBuilder, connctx: ConnectionContext) {
    val bytes = toBinary(connctx)
    builder.putInt(bytes.length)(byteOrder)
    builder.putBytes(bytes)
  }

  def fromByteIterator(data: ByteIterator) = {
    val ctxBytes = Array.ofDim[Byte](data.getInt(byteOrder))
    data.getBytes(ctxBytes)
    ConnectionContextSerializer.fromBinary(ctxBytes).asInstanceOf[ConnectionContext]
  }
}
class ConnectionContextSerializer extends Serializer {
  implicit val byteOrder = ByteOrder.BIG_ENDIAN

  final def includeManifest: Boolean = false

  final def identifier: Int = 2001

  final def toBinary(o: AnyRef): Array[Byte] = {
    o match {
      case x: ConnectionContext =>
        val builder = ByteString.newBuilder

        StringSerializer.appendToBuilder(builder, x.sessionId)
        StringSerializer.appendToBuilder(builder, x.transport.ID)
        StringSerializer.appendToBuilder(builder, x.query.render(new StringRendering).get)

        builder.putByte(if (x.isConnected) 0x01 else 0x00)

        x.origins foreach { origin =>
          StringSerializer.appendToBuilder(builder, origin.render(new StringRendering).get)
        }

        builder.result.toArray
      case _ => Array[Byte]()
    }
  }

  final def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]): AnyRef = {
    val data = ByteString(bytes).iterator

    val sessionId = StringSerializer.fromByteIterator(data)
    val transId = StringSerializer.fromByteIterator(data)
    val query = Query(StringSerializer.fromByteIterator(data))

    val isConnected = data.getByte == 0x01

    val origins = mutable.ListBuffer[HttpOrigin]()
    while (data.nonEmpty) {
      origins.append(HttpOrigin(StringSerializer.fromByteIterator(data)))
    }

    val ctx = new ConnectionContext(sessionId, query, origins.toList)
    ctx.transport = Transport.transportIds.getOrElse(transId, transport.Empty)
    ctx.isConnected = isConnected

    ctx
  }

}

class OnPacketSerializer extends Serializer {
  implicit val byteOrder = ByteOrder.BIG_ENDIAN

  final def includeManifest: Boolean = false

  final def identifier: Int = 2002

  final def toBinary(o: AnyRef): Array[Byte] = {
    o match {
      case ConnectionActive.OnPacket(packet: Packet, connContext: ConnectionContext) =>
        val builder = ByteString.newBuilder

        PacketSerializer.appendToBuilder(builder, packet)
        ConnectionContextSerializer.appendToBuilder(builder, connContext)

        builder.result.toArray
      case _ => Array[Byte]()
    }
  }

  final def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]): AnyRef = {
    val data = ByteString(bytes).iterator

    val packet = PacketSerializer.fromByteIterator(data)
    val ctx = ConnectionContextSerializer.fromByteIterator(data)

    ConnectionActive.OnPacket(packet, ctx)
  }
}

class OnBroadcastSerializer extends Serializer {
  implicit val byteOrder = ByteOrder.BIG_ENDIAN

  final def includeManifest: Boolean = false

  final def identifier: Int = 2003

  final def toBinary(o: AnyRef): Array[Byte] = {
    o match {
      case ConnectionActive.OnBroadcast(sessionId, room, packet) =>
        val builder = ByteString.newBuilder

        StringSerializer.appendToBuilder(builder, sessionId)
        StringSerializer.appendToBuilder(builder, room)
        PacketSerializer.appendToBuilder(builder, packet)

        builder.result.toArray
      case _ => Array[Byte]()
    }
  }

  final def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]): AnyRef = {
    val data = ByteString(bytes).iterator

    val sessionId = StringSerializer.fromByteIterator(data)
    val room = StringSerializer.fromByteIterator(data)
    val packet = PacketSerializer.fromByteIterator(data)

    ConnectionActive.OnBroadcast(sessionId, room, packet)
  }
}

class CommandSerializer(val system: ExtendedActorSystem) extends Serializer {
  implicit val byteOrder = ByteOrder.BIG_ENDIAN

  final def includeManifest: Boolean = true

  final def identifier: Int = 2004

  private val fromBinaryMap = collection.immutable.HashMap[Class[_ <: ConnectionActive.Command], Array[Byte] => AnyRef](
    classOf[ConnectionActive.CreateSession] -> (bytes => toCreateSession(bytes)),
    classOf[ConnectionActive.Connecting] -> (bytes => toConnecting(bytes)),
    classOf[ConnectionActive.Closing] -> (bytes => toClosing(bytes)),
    classOf[ConnectionActive.OnGet] -> (bytes => toOnGet(bytes)),
    classOf[ConnectionActive.OnPost] -> (bytes => toOnPost(bytes)),
    classOf[ConnectionActive.OnFrame] -> (bytes => toOnFrame(bytes)),
    classOf[ConnectionActive.SendMessage] -> (bytes => toSendMessage(bytes)),
    classOf[ConnectionActive.SendJson] -> (bytes => toSendJson(bytes)),
    classOf[ConnectionActive.SendEvent] -> (bytes => toSendEvent(bytes)),
    classOf[ConnectionActive.SendPackets] -> (bytes => toSendPackets(bytes)),
    classOf[ConnectionActive.SendAck] -> (bytes => toSendAck(bytes)),
    classOf[ConnectionActive.SubscribeBroadcast] -> (bytes => toSubscribeBroadcast(bytes)),
    classOf[ConnectionActive.UnsubscribeBroadcast] -> (bytes => toUnsubscribeBroadcast(bytes)),
    classOf[ConnectionActive.GetStatus] -> (bytes => toGetStatus(bytes)),
    classOf[ConnectionActive.Broadcast] -> (bytes => toBroadcast(bytes)))

  final def toBinary(o: AnyRef): Array[Byte] = {
    o match {
      case cmd: ConnectionActive.CreateSession        => fromCreateSession(cmd)
      case cmd: ConnectionActive.Connecting           => fromConnecting(cmd)
      case cmd: ConnectionActive.Closing              => fromClosing(cmd)
      case cmd: ConnectionActive.OnGet                => fromOnGet(cmd)
      case cmd: ConnectionActive.OnPost               => fromOnPost(cmd)
      case cmd: ConnectionActive.OnFrame              => fromOnFrame(cmd)
      case cmd: ConnectionActive.SendMessage          => fromSendMessage(cmd)
      case cmd: ConnectionActive.SendJson             => fromSendJson(cmd)
      case cmd: ConnectionActive.SendEvent            => fromSendEvent(cmd)
      case cmd: ConnectionActive.SendPackets          => fromSendPackets(cmd)
      case cmd: ConnectionActive.SendAck              => fromSendAck(cmd)
      case cmd: ConnectionActive.SubscribeBroadcast   => fromSubscribeBroadcast(cmd)
      case cmd: ConnectionActive.UnsubscribeBroadcast => fromUnsubscribeBroadcast(cmd)
      case cmd: ConnectionActive.GetStatus            => fromGetStatus(cmd)
      case cmd: ConnectionActive.Broadcast            => fromBroadcast(cmd)
    }
  }

  final def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]): AnyRef = {
    manifest match {
      case Some(clazz) => fromBinaryMap.get(clazz.asInstanceOf[Class[ConnectionActive.Command]]) match {
        case Some(f) => f(bytes)
        case None    => throw new IllegalArgumentException(s"Unimplemented deserialization of message class $clazz in CommandSerializer")
      }
      case _ => throw new IllegalArgumentException("Need a command message class to be able to deserialize bytes in CommandSerializer")
    }
  }

  final def fromCreateSession(cmd: ConnectionActive.CreateSession) = cmd.sessionId.getBytes

  final def toCreateSession(bytes: Array[Byte]) = ConnectionActive.CreateSession(new String(bytes))

  final def fromConnecting(cmd: ConnectionActive.Connecting) = {
    val builder = ByteString.newBuilder

    StringSerializer.appendToBuilder(builder, cmd.sessionId)
    StringSerializer.appendToBuilder(builder, cmd.query.render(new StringRendering).get)
    if (cmd.transport != null) {
      StringSerializer.appendToBuilder(builder, cmd.transport.ID)
    } else {
      StringSerializer.appendToBuilder(builder, "")
    }
    StringSerializer.appendToBuilder(builder, Serialization.serializedActorPath(cmd.transportConnection))
    cmd.origins.foreach { origin =>
      StringSerializer.appendToBuilder(builder, origin.render(new StringRendering).get)
    }

    builder.result.toArray
  }

  final def toConnecting(bytes: Array[Byte]) = {
    val data = ByteString(bytes).iterator

    val sessionId = StringSerializer.fromByteIterator(data)
    val query = Query.apply(StringSerializer.fromByteIterator(data))
    val transport = Transport.transportIds.getOrElse(StringSerializer.fromByteIterator(data), null)
    val tranportConnection = system.provider.resolveActorRef(StringSerializer.fromByteIterator(data))
    val origins = mutable.ListBuffer[HttpOrigin]()
    while (data.nonEmpty) {
      origins.append(HttpOrigin(StringSerializer.fromByteIterator(data)))
    }
    ConnectionActive.Connecting(sessionId, query, origins.toList, tranportConnection, transport)
  }

  final def fromClosing(cmd: ConnectionActive.Closing) = {
    val builder = ByteString.newBuilder

    StringSerializer.appendToBuilder(builder, cmd.sessionId)
    StringSerializer.appendToBuilder(builder, Serialization.serializedActorPath(cmd.transportConnection))

    builder.result.toArray
  }

  final def toClosing(bytes: Array[Byte]) = {
    val data = ByteString(bytes).iterator

    val sessionId = StringSerializer.fromByteIterator(data)
    val ref = system.provider.resolveActorRef(StringSerializer.fromByteIterator(data))

    ConnectionActive.Closing(sessionId, ref)
  }

  final def fromOnGet(cmd: ConnectionActive.OnGet) = {
    val builder = ByteString.newBuilder

    StringSerializer.appendToBuilder(builder, cmd.sessionId)
    StringSerializer.appendToBuilder(builder, Serialization.serializedActorPath(cmd.transportConnection))

    builder.result.toArray
  }

  final def toOnGet(bytes: Array[Byte]) = {
    val data = ByteString(bytes).iterator

    val sessionId = StringSerializer.fromByteIterator(data)
    val ref = system.provider.resolveActorRef(StringSerializer.fromByteIterator(data))

    ConnectionActive.OnGet(sessionId, ref)
  }

  final def fromOnPost(cmd: ConnectionActive.OnPost) = {
    val builder = ByteString.newBuilder

    StringSerializer.appendToBuilder(builder, cmd.sessionId)
    StringSerializer.appendToBuilder(builder, Serialization.serializedActorPath(cmd.transportConnection))
    builder.append(cmd.payload)

    builder.result.toArray
  }

  final def toOnPost(bytes: Array[Byte]) = {
    val data = ByteString(bytes).iterator

    val sessionId = StringSerializer.fromByteIterator(data)
    val ref = system.provider.resolveActorRef(StringSerializer.fromByteIterator(data))

    val payload = data.toByteString

    ConnectionActive.OnPost(sessionId, ref, payload)
  }

  final def fromOnFrame(cmd: ConnectionActive.OnFrame) = {
    val builder = ByteString.newBuilder

    StringSerializer.appendToBuilder(builder, cmd.sessionId)
    builder.append(cmd.payload)

    builder.result.toArray
  }

  final def toOnFrame(bytes: Array[Byte]) = {
    val data = ByteString(bytes).iterator

    val sessionId = StringSerializer.fromByteIterator(data)
    val payload = Array.ofDim[Byte](data.len)
    data.getBytes(payload)
    ConnectionActive.OnFrame(sessionId, ByteString(payload))
  }

  final def fromSendMessage(cmd: ConnectionActive.SendMessage) = {
    val builder = ByteString.newBuilder

    StringSerializer.appendToBuilder(builder, cmd.sessionId)
    StringSerializer.appendToBuilder(builder, cmd.endpoint)
    StringSerializer.appendToBuilder(builder, cmd.msg)

    builder.result.toArray
  }

  final def toSendMessage(bytes: Array[Byte]) = {
    val data = ByteString(bytes).iterator

    ConnectionActive.SendMessage(StringSerializer.fromByteIterator(data), StringSerializer.fromByteIterator(data), StringSerializer.fromByteIterator(data))
  }

  final def fromSendJson(cmd: ConnectionActive.SendJson) = {
    val builder = ByteString.newBuilder

    StringSerializer.appendToBuilder(builder, cmd.sessionId)
    StringSerializer.appendToBuilder(builder, cmd.endpoint)
    StringSerializer.appendToBuilder(builder, cmd.json)

    builder.result.toArray
  }

  final def toSendJson(bytes: Array[Byte]) = {
    val data = ByteString(bytes).iterator

    ConnectionActive.SendJson(StringSerializer.fromByteIterator(data), StringSerializer.fromByteIterator(data), StringSerializer.fromByteIterator(data))
  }

  final def fromSendEvent(cmd: ConnectionActive.SendEvent) = {
    val builder = ByteString.newBuilder

    StringSerializer.appendToBuilder(builder, cmd.sessionId)
    StringSerializer.appendToBuilder(builder, cmd.endpoint)
    StringSerializer.appendToBuilder(builder, cmd.name)

    cmd.args match {
      case Left(s) =>
        StringSerializer.appendToBuilder(builder, "l")
        StringSerializer.appendToBuilder(builder, s)
      case Right(ss) =>
        StringSerializer.appendToBuilder(builder, "r")
        ss.foreach(StringSerializer.appendToBuilder(builder, _))
    }

    builder.result.toArray
  }

  final def toSendEvent(bytes: Array[Byte]) = {
    val data = ByteString(bytes).iterator

    val sessionId = StringSerializer.fromByteIterator(data)
    val endpoint = StringSerializer.fromByteIterator(data)
    val name = StringSerializer.fromByteIterator(data)
    val lr = StringSerializer.fromByteIterator(data)

    val ss = mutable.ListBuffer[String]()
    while (data.nonEmpty) {
      ss.append(StringSerializer.fromByteIterator(data))
    }
    val args: Either[String, Seq[String]] = lr match {
      case "l" if ss.nonEmpty => Left[String, Seq[String]](ss(0))
      case "r"                => Right[String, Seq[String]](ss.toList)
      case _                  => Left[String, Seq[String]]("[]")
    }
    ConnectionActive.SendEvent(sessionId, endpoint, name, args)
  }

  final def fromSendPackets(cmd: ConnectionActive.SendPackets) = {
    val builder = ByteString.newBuilder

    StringSerializer.appendToBuilder(builder, cmd.sessionId)
    cmd.packets.foreach(PacketSerializer.appendToBuilder(builder, _))

    builder.result.toArray
  }

  final def toSendPackets(bytes: Array[Byte]) = {
    val data = ByteString(bytes).iterator

    val sessionId = StringSerializer.fromByteIterator(data)
    val packets = mutable.ListBuffer[Packet]()
    while (data.nonEmpty) {
      packets.append(PacketSerializer.fromByteIterator(data))
    }
    ConnectionActive.SendPackets(sessionId, packets.toList)
  }

  final def fromSendAck(cmd: ConnectionActive.SendAck) = {
    val builder = ByteString.newBuilder

    StringSerializer.appendToBuilder(builder, cmd.sessionId)
    PacketSerializer.appendToBuilder(builder, cmd.originalPacket)
    StringSerializer.appendToBuilder(builder, cmd.args)

    builder.result.toArray
  }

  final def toSendAck(bytes: Array[Byte]) = {
    val data = ByteString(bytes).iterator

    val sessionId = StringSerializer.fromByteIterator(data)
    val packet = PacketSerializer.fromByteIterator(data).asInstanceOf[DataPacket]
    val args = StringSerializer.fromByteIterator(data)

    ConnectionActive.SendAck(sessionId, packet, args)
  }

  final def fromSubscribeBroadcast(cmd: ConnectionActive.SubscribeBroadcast) = {
    val builder = ByteString.newBuilder

    StringSerializer.appendToBuilder(builder, cmd.sessionId)
    StringSerializer.appendToBuilder(builder, cmd.endpoint)
    StringSerializer.appendToBuilder(builder, cmd.room)

    builder.result.toArray
  }

  final def toSubscribeBroadcast(bytes: Array[Byte]) = {
    val data = ByteString(bytes).iterator

    ConnectionActive.SubscribeBroadcast(StringSerializer.fromByteIterator(data), StringSerializer.fromByteIterator(data), StringSerializer.fromByteIterator(data))
  }

  final def fromUnsubscribeBroadcast(cmd: ConnectionActive.UnsubscribeBroadcast) = {
    val builder = ByteString.newBuilder

    StringSerializer.appendToBuilder(builder, cmd.sessionId)
    StringSerializer.appendToBuilder(builder, cmd.endpoint)
    StringSerializer.appendToBuilder(builder, cmd.room)

    builder.result.toArray
  }

  final def toUnsubscribeBroadcast(bytes: Array[Byte]) = {
    val data = ByteString(bytes).iterator

    ConnectionActive.UnsubscribeBroadcast(StringSerializer.fromByteIterator(data), StringSerializer.fromByteIterator(data), StringSerializer.fromByteIterator(data))

  }

  final def fromGetStatus(cmd: ConnectionActive.GetStatus) = {
    val builder = ByteString.newBuilder
    StringSerializer.appendToBuilder(builder, cmd.sessionId)
    builder.result.toArray
  }

  final def toGetStatus(bytes: Array[Byte]) = {
    val data = ByteString(bytes).iterator
    val sessionId = StringSerializer.fromByteIterator(data)
    ConnectionActive.GetStatus(sessionId)
  }

  final def fromBroadcast(cmd: ConnectionActive.Broadcast) = {
    val builder = ByteString.newBuilder

    StringSerializer.appendToBuilder(builder, cmd.sessionId)
    StringSerializer.appendToBuilder(builder, cmd.room)
    PacketSerializer.appendToBuilder(builder, cmd.packet)

    builder.result.toArray
  }

  final def toBroadcast(bytes: Array[Byte]) = {
    val data = ByteString(bytes).iterator

    val sessionId = StringSerializer.fromByteIterator(data)
    val room = StringSerializer.fromByteIterator(data)
    val packet = PacketSerializer.fromByteIterator(data)

    ConnectionActive.Broadcast(sessionId, room, packet)
  }

}

class StatusSerializer extends Serializer {
  implicit val byteOrder = ByteOrder.BIG_ENDIAN

  final def includeManifest: Boolean = false

  final def identifier: Int = 2006

  final def toBinary(o: AnyRef): Array[Byte] = {
    o match {
      case ConnectionActive.Status(sessionId, connectionTime, location) =>
        val builder = ByteString.newBuilder

        StringSerializer.appendToBuilder(builder, sessionId)
        builder.putLong(connectionTime)
        StringSerializer.appendToBuilder(builder, location)

        builder.result.toArray
      case _ => Array[Byte]()
    }
  }

  final def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]): AnyRef = {
    val data = ByteString(bytes).iterator

    val sessionId = StringSerializer.fromByteIterator(data)
    val connectionTime = data.getLong
    val location = StringSerializer.fromByteIterator(data)

    ConnectionActive.Status(sessionId, connectionTime, location)
  }
}