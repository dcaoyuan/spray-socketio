package spray.contrib.socketio.serializer

import akka.actor.ExtendedActorSystem
import akka.serialization.{ Serialization, Serializer }
import akka.util.{ ByteIterator, ByteStringBuilder, ByteString }
import java.nio.ByteOrder
import scala.collection.mutable.ListBuffer
import scala.util.Failure
import scala.util.Success
import spray.can.websocket.frame.{ FrameParser, FrameRender, TextFrame, Frame }
import spray.contrib.socketio.ConnectionActive._
import spray.contrib.socketio.ConnectionContext
import spray.contrib.socketio.ConnectionActive.OnPacket
import spray.contrib.socketio.ConnectionActive.OnFrame
import spray.contrib.socketio.ConnectionActive.SendJson
import spray.contrib.socketio.ConnectionActive.SendMessage
import spray.contrib.socketio.packet.{ DataPacket, Packet, PacketParser }
import spray.contrib.socketio.transport.Transport
import spray.http.{ HttpOrigin, StringRendering }
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

// ConnectionContext(val sessionId: String, val query: Uri.Query, val origins: Seq[HttpOrigin])
object ConnectionContextSerializer extends ConnectionContextSerializer
class ConnectionContextSerializer extends Serializer {
  implicit val byteOrder = ByteOrder.BIG_ENDIAN

  final def includeManifest: Boolean = false

  final def identifier: Int = 2001

  final def toBinary(o: AnyRef): Array[Byte] = {
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
        x.origins.foreach { origin =>
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
    val query = Query.apply(StringSerializer.fromByteIterator(data))
    val origins = ListBuffer[HttpOrigin]()
    while (data.nonEmpty) {
      origins.append(HttpOrigin(StringSerializer.fromByteIterator(data)))
    }

    val ctx = new ConnectionContext(sessionId, query, origins)
    ctx.bindTransport(Transport.transportIds.getOrElse(transId, null))

    ctx
  }

  final def appendToBuilder(builder: ByteStringBuilder, connctx: ConnectionContext) {
    val bytes = toBinary(connctx)
    builder.putInt(bytes.length)
    builder.putBytes(bytes)
  }

  final def fromByteIterator(data: ByteIterator) = {
    val ctxBytes = Array.ofDim[Byte](data.getInt)
    data.getBytes(ctxBytes)
    ConnectionContextSerializer.fromBinary(ctxBytes).asInstanceOf[ConnectionContext]
  }
}

class OnPacketSerializer extends Serializer {
  implicit val byteOrder = ByteOrder.BIG_ENDIAN

  final def includeManifest: Boolean = false

  final def identifier: Int = 2002

  final def toBinary(o: AnyRef): Array[Byte] = {
    o match {
      case OnPacket(packet: Packet, connContext: ConnectionContext) =>
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

    OnPacket(packet, ctx)
  }
}

class OnBroadcastSerializer extends Serializer {
  implicit val byteOrder = ByteOrder.BIG_ENDIAN

  final def includeManifest: Boolean = false

  final def identifier: Int = 2003

  final def toBinary(o: AnyRef): Array[Byte] = {
    o match {
      case OnBroadcast(sessionId, room, packet) =>
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

    OnBroadcast(sessionId, room, packet)
  }
}

class CommandSerializer(val system: ExtendedActorSystem) extends Serializer {
  implicit val byteOrder = ByteOrder.BIG_ENDIAN

  final def includeManifest: Boolean = true

  final def identifier: Int = 2004

  private val fromBinaryMap = collection.immutable.HashMap[Class[_ <: Command], Array[Byte] => AnyRef](
    classOf[CreateSession] -> (bytes => toCreateSession(bytes)),
    classOf[Connecting] -> (bytes => toConnecting(bytes)),
    classOf[OnGet] -> (bytes => toOnGet(bytes)),
    classOf[OnPost] -> (bytes => toOnPost(bytes)),
    classOf[OnFrame] -> (bytes => toOnFrame(bytes)),
    classOf[SendMessage] -> (bytes => toSendMessage(bytes)),
    classOf[SendJson] -> (bytes => toSendJson(bytes)),
    classOf[SendEvent] -> (bytes => toSendEvent(bytes)),
    classOf[SendPackets] -> (bytes => toSendPackets(bytes)),
    classOf[SendAck] -> (bytes => toSendAck(bytes)),
    classOf[SubscribeBroadcast] -> (bytes => toSubscribeBroadcast(bytes)),
    classOf[UnsubscribeBroadcast] -> (bytes => toUnsubscribeBroadcast(bytes)),
    classOf[Broadcast] -> (bytes => toBroadcast(bytes)))

  final def toBinary(o: AnyRef): Array[Byte] = {
    o match {
      case cmd: CreateSession        => fromCreateSession(cmd)
      case cmd: Connecting           => fromConnecting(cmd)
      case cmd: OnGet                => fromOnGet(cmd)
      case cmd: OnPost               => fromOnPost(cmd)
      case cmd: OnFrame              => fromOnFrame(cmd)
      case cmd: SendMessage          => fromSendMessage(cmd)
      case cmd: SendJson             => fromSendJson(cmd)
      case cmd: SendEvent            => fromSendEvent(cmd)
      case cmd: SendPackets          => fromSendPackets(cmd)
      case cmd: SendAck              => fromSendAck(cmd)
      case cmd: SubscribeBroadcast   => fromSubscribeBroadcast(cmd)
      case cmd: UnsubscribeBroadcast => fromUnsubscribeBroadcast(cmd)
      case cmd: Broadcast            => fromBroadcast(cmd)
    }
  }

  final def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]): AnyRef = {
    manifest match {
      case Some(clazz) => fromBinaryMap.get(clazz.asInstanceOf[Class[Command]]) match {
        case Some(f) => f(bytes)
        case None    => throw new IllegalArgumentException(s"Unimplemented deserialization of message class $clazz in CommandSerializer")
      }
      case _ => throw new IllegalArgumentException("Need a command message class to be able to deserialize bytes in CommandSerializer")
    }
  }

  final def fromCreateSession(cmd: CreateSession) = cmd.sessionId.getBytes

  final def toCreateSession(bytes: Array[Byte]) = CreateSession(new String(bytes))

  final def fromConnecting(cmd: Connecting) = {
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
    val ref = system.actorFor(StringSerializer.fromByteIterator(data))
    val origins = ListBuffer[HttpOrigin]()
    while (data.nonEmpty) {
      origins.append(HttpOrigin(StringSerializer.fromByteIterator(data)))
    }
    Connecting(sessionId, query, origins, ref, transport)
  }

  final def fromOnGet(cmd: OnGet) = {
    val builder = ByteString.newBuilder

    StringSerializer.appendToBuilder(builder, cmd.sessionId)
    StringSerializer.appendToBuilder(builder, Serialization.serializedActorPath(cmd.transportConnection))

    builder.result.toArray
  }

  final def toOnGet(bytes: Array[Byte]) = {
    val data = ByteString(bytes).iterator

    val sessionId = StringSerializer.fromByteIterator(data)
    val ref = system.actorFor(StringSerializer.fromByteIterator(data))

    OnGet(sessionId, ref)
  }

  final def fromOnPost(cmd: OnPost) = {
    val builder = ByteString.newBuilder

    StringSerializer.appendToBuilder(builder, cmd.sessionId)
    StringSerializer.appendToBuilder(builder, Serialization.serializedActorPath(cmd.transportConnection))
    builder.append(cmd.payload)

    builder.result.toArray
  }

  final def toOnPost(bytes: Array[Byte]) = {
    val data = ByteString(bytes).iterator

    val sessionId = StringSerializer.fromByteIterator(data)
    val ref = system.actorFor(StringSerializer.fromByteIterator(data))
    val payload = data.toByteString

    OnPost(sessionId, ref, payload)
  }

  final def fromOnFrame(cmd: OnFrame) = {
    val builder = ByteString.newBuilder

    StringSerializer.appendToBuilder(builder, cmd.sessionId)
    builder.append(FrameRender(cmd.frame))

    builder.result.toArray
  }

  final def toOnFrame(bytes: Array[Byte]) = {
    val data = ByteString(bytes).iterator

    val sessionId = StringSerializer.fromByteIterator(data)

    var r: OnFrame = null
    new FrameParser().onReceive(data) {
      case FrameParser.Success(frame: TextFrame) => r = OnFrame(sessionId, frame)
      case _                                     =>
    }
    r
  }

  final def fromSendMessage(cmd: SendMessage) = {
    val builder = ByteString.newBuilder

    StringSerializer.appendToBuilder(builder, cmd.sessionId)
    StringSerializer.appendToBuilder(builder, cmd.endpoint)
    StringSerializer.appendToBuilder(builder, cmd.msg)

    builder.result.toArray
  }

  final def toSendMessage(bytes: Array[Byte]) = {
    val data = ByteString(bytes).iterator

    SendMessage(StringSerializer.fromByteIterator(data), StringSerializer.fromByteIterator(data), StringSerializer.fromByteIterator(data))
  }

  final def fromSendJson(cmd: SendJson) = {
    val builder = ByteString.newBuilder

    StringSerializer.appendToBuilder(builder, cmd.sessionId)
    StringSerializer.appendToBuilder(builder, cmd.endpoint)
    StringSerializer.appendToBuilder(builder, cmd.json)

    builder.result.toArray
  }

  final def toSendJson(bytes: Array[Byte]) = {
    val data = ByteString(bytes).iterator

    SendJson(StringSerializer.fromByteIterator(data), StringSerializer.fromByteIterator(data), StringSerializer.fromByteIterator(data))
  }

  final def fromSendEvent(cmd: SendEvent) = {
    val builder = ByteString.newBuilder

    StringSerializer.appendToBuilder(builder, cmd.sessionId)
    StringSerializer.appendToBuilder(builder, cmd.endpoint)
    StringSerializer.appendToBuilder(builder, cmd.name)

    cmd.args match {
      case Left(s)   => StringSerializer.appendToBuilder(builder, s)
      case Right(ss) => ss.foreach(StringSerializer.appendToBuilder(builder, _))
    }

    builder.result.toArray
  }

  final def toSendEvent(bytes: Array[Byte]) = {
    val data = ByteString(bytes).iterator

    val sessionId = StringSerializer.fromByteIterator(data)
    val endpoint = StringSerializer.fromByteIterator(data)
    val name = StringSerializer.fromByteIterator(data)

    val ss = ListBuffer[String]()
    while (data.nonEmpty) {
      ss.append(StringSerializer.fromByteIterator(data))
    }
    val args: Either[String, Seq[String]] = ss.toList match {
      case (s: String) :: Nil => Left[String, Seq[String]](s)
      case l                  => Right[String, Seq[String]](l.toSeq)
    }
    SendEvent(sessionId, endpoint, name, args)
  }

  final def fromSendPackets(cmd: SendPackets) = {
    val builder = ByteString.newBuilder

    StringSerializer.appendToBuilder(builder, cmd.sessionId)
    cmd.packets.foreach(PacketSerializer.appendToBuilder(builder, _))

    builder.result.toArray
  }

  final def toSendPackets(bytes: Array[Byte]) = {
    val data = ByteString(bytes).iterator

    val sessionId = StringSerializer.fromByteIterator(data)
    val packets = ListBuffer[Packet]()
    while (data.nonEmpty) {
      packets.append(PacketSerializer.fromByteIterator(data))
    }
    SendPackets(sessionId, packets)
  }

  final def fromSendAck(cmd: SendAck) = {
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

    SendAck(sessionId, packet, args)
  }

  final def fromSubscribeBroadcast(cmd: SubscribeBroadcast) = {
    val builder = ByteString.newBuilder

    StringSerializer.appendToBuilder(builder, cmd.sessionId)
    StringSerializer.appendToBuilder(builder, cmd.endpoint)
    StringSerializer.appendToBuilder(builder, cmd.room)

    builder.result.toArray
  }

  final def toSubscribeBroadcast(bytes: Array[Byte]) = {
    val data = ByteString(bytes).iterator

    SubscribeBroadcast(StringSerializer.fromByteIterator(data), StringSerializer.fromByteIterator(data), StringSerializer.fromByteIterator(data))
  }

  final def fromUnsubscribeBroadcast(cmd: UnsubscribeBroadcast) = {
    val builder = ByteString.newBuilder

    StringSerializer.appendToBuilder(builder, cmd.sessionId)
    StringSerializer.appendToBuilder(builder, cmd.endpoint)
    StringSerializer.appendToBuilder(builder, cmd.room)

    builder.result.toArray
  }

  final def toUnsubscribeBroadcast(bytes: Array[Byte]) = {
    val data = ByteString(bytes).iterator

    UnsubscribeBroadcast(StringSerializer.fromByteIterator(data), StringSerializer.fromByteIterator(data), StringSerializer.fromByteIterator(data))

  }

  final def fromBroadcast(cmd: Broadcast) = {
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

    Broadcast(sessionId, room, packet)
  }

}

class EventSerializer(val system: ExtendedActorSystem) extends Serializer {
  implicit val byteOrder = ByteOrder.BIG_ENDIAN

  final def includeManifest: Boolean = true

  final def identifier: Int = 2005

  private val fromBinaryMap = collection.immutable.HashMap[Class[_ <: Event], Array[Byte] => AnyRef](
    classOf[ConnectingEvent] -> (bytes => toConnectingEvent(bytes)),
    classOf[SubscribeBroadcastEvent] -> (bytes => toSubscribeBroadcastEvent(bytes)),
    classOf[UnsubscribeBroadcastEvent] -> (bytes => toUnsubscribeBroadcastEvent(bytes)))

  final def toBinary(o: AnyRef): Array[Byte] = {
    o match {
      case evt: ConnectingEvent           => fromConnectingEvent(evt)
      case evt: SubscribeBroadcastEvent   => fromSubscribeBroadcastEvent(evt)
      case evt: UnsubscribeBroadcastEvent => fromUnsubscribeBroadcastEvent(evt)
    }
  }

  final def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]): AnyRef = {
    manifest match {
      case Some(clazz) => fromBinaryMap.get(clazz.asInstanceOf[Class[Event]]) match {
        case Some(f) => f(bytes)
        case None    => throw new IllegalArgumentException(s"Unimplemented deserialization of message class $clazz in CommandSerializer")
      }
      case _ => throw new IllegalArgumentException("Need a command message class to be able to deserialize bytes in CommandSerializer")
    }
  }

  final def fromConnectingEvent(evt: ConnectingEvent) = {
    val builder = ByteString.newBuilder

    StringSerializer.appendToBuilder(builder, evt.sessionId)
    StringSerializer.appendToBuilder(builder, evt.query.render(new StringRendering).get)
    if (evt.transport != null) {
      StringSerializer.appendToBuilder(builder, evt.transport.ID)
    } else {
      StringSerializer.appendToBuilder(builder, "")
    }
    StringSerializer.appendToBuilder(builder, Serialization.serializedActorPath(evt.transportConnection))
    evt.origins.foreach { origin =>
      StringSerializer.appendToBuilder(builder, origin.render(new StringRendering).get)
    }

    builder.result.toArray
  }

  final def toConnectingEvent(bytes: Array[Byte]) = {
    val data = ByteString(bytes).iterator

    val sessionId = StringSerializer.fromByteIterator(data)
    val query = Query.apply(StringSerializer.fromByteIterator(data))
    val transport = Transport.transportIds.getOrElse(StringSerializer.fromByteIterator(data), null)
    val ref = system.actorFor(StringSerializer.fromByteIterator(data))
    val origins = ListBuffer[HttpOrigin]()
    while (data.nonEmpty) {
      origins.append(HttpOrigin(StringSerializer.fromByteIterator(data)))
    }
    ConnectingEvent(sessionId, query, origins, ref, transport)
  }

  final def fromSubscribeBroadcastEvent(evt: SubscribeBroadcastEvent) = {
    val builder = ByteString.newBuilder

    StringSerializer.appendToBuilder(builder, evt.sessionId)
    StringSerializer.appendToBuilder(builder, evt.endpoint)
    StringSerializer.appendToBuilder(builder, evt.room)

    builder.result.toArray
  }

  final def toSubscribeBroadcastEvent(bytes: Array[Byte]) = {
    val data = ByteString(bytes).iterator

    SubscribeBroadcastEvent(StringSerializer.fromByteIterator(data), StringSerializer.fromByteIterator(data), StringSerializer.fromByteIterator(data))
  }

  final def fromUnsubscribeBroadcastEvent(evt: UnsubscribeBroadcastEvent) = {
    val builder = ByteString.newBuilder

    StringSerializer.appendToBuilder(builder, evt.sessionId)
    StringSerializer.appendToBuilder(builder, evt.endpoint)
    StringSerializer.appendToBuilder(builder, evt.room)

    builder.result.toArray
  }

  final def toUnsubscribeBroadcastEvent(bytes: Array[Byte]) = {
    val data = ByteString(bytes).iterator

    UnsubscribeBroadcastEvent(StringSerializer.fromByteIterator(data), StringSerializer.fromByteIterator(data), StringSerializer.fromByteIterator(data))

  }
}