package spray.contrib.socketio.serializer

import akka.actor.ExtendedActorSystem
import akka.serialization.{ Serialization, Serializer }
import akka.util.{ ByteIterator, ByteStringBuilder, ByteString }
import java.nio.ByteOrder
import scala.collection.mutable
import scala.collection.immutable
import scala.util.Failure
import scala.util.Success
import spray.can.websocket.frame.{ FrameParser, FrameRender, Frame }
import spray.contrib.socketio
import spray.contrib.socketio.ConnectionSession
import spray.contrib.socketio.ConnectionContext
import spray.contrib.socketio.namespace.Namespace
import spray.contrib.socketio.packet.{ DataPacket, Packet, PacketParser, ConnectPacket, DisconnectPacket, MessagePacket, JsonPacket, EventPacket, NoopPacket }
import spray.contrib.socketio.transport
import spray.contrib.socketio.transport.Transport
import spray.http.{ HttpOrigin, StringRendering }
import spray.http.Uri.Query

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

class ConnectionSessionStateSerializer(val system: ExtendedActorSystem) extends Serializer {
  implicit val byteOrder = ByteOrder.BIG_ENDIAN

  final def includeManifest: Boolean = false

  final def identifier: Int = 2001

  final def toBinary(o: AnyRef): Array[Byte] = {
    o match {
      case x: ConnectionSession.State =>
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

    new ConnectionSession.State(ctx, transportConn, topics)
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

  final def identifier: Int = 2002

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

class OnBroadcastSerializer extends Serializer {
  implicit val byteOrder = ByteOrder.BIG_ENDIAN

  final def includeManifest: Boolean = false

  final def identifier: Int = 2004

  final def toBinary(o: AnyRef): Array[Byte] = {
    o match {
      case ConnectionSession.OnBroadcast(sessionId, room, packet) =>
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

    ConnectionSession.OnBroadcast(sessionId, room, packet)
  }
}

class ConnectionSessionCommandSerializer(val system: ExtendedActorSystem) extends Serializer {
  implicit val byteOrder = ByteOrder.BIG_ENDIAN

  final def includeManifest: Boolean = true

  final def identifier: Int = 2005

  private val fromBinaryMap = collection.immutable.HashMap[Class[_ <: ConnectionSession.Command], Array[Byte] => AnyRef](
    classOf[ConnectionSession.CreateSession] -> (bytes => toCreateSession(bytes)),
    classOf[ConnectionSession.Connecting] -> (bytes => toConnecting(bytes)),
    classOf[ConnectionSession.Closing] -> (bytes => toClosing(bytes)),
    classOf[ConnectionSession.OnGet] -> (bytes => toOnGet(bytes)),
    classOf[ConnectionSession.OnPost] -> (bytes => toOnPost(bytes)),
    classOf[ConnectionSession.OnFrame] -> (bytes => toOnFrame(bytes)),
    classOf[ConnectionSession.SendMessage] -> (bytes => toSendMessage(bytes)),
    classOf[ConnectionSession.SendJson] -> (bytes => toSendJson(bytes)),
    classOf[ConnectionSession.SendEvent] -> (bytes => toSendEvent(bytes)),
    classOf[ConnectionSession.SendPackets] -> (bytes => toSendPackets(bytes)),
    classOf[ConnectionSession.SendAck] -> (bytes => toSendAck(bytes)),
    classOf[ConnectionSession.SubscribeBroadcast] -> (bytes => toSubscribeBroadcast(bytes)),
    classOf[ConnectionSession.UnsubscribeBroadcast] -> (bytes => toUnsubscribeBroadcast(bytes)),
    classOf[ConnectionSession.AskStatus] -> (bytes => toAskStatus(bytes)),
    classOf[ConnectionSession.Broadcast] -> (bytes => toBroadcast(bytes)))

  final def toBinary(o: AnyRef): Array[Byte] = {
    o match {
      case cmd: ConnectionSession.CreateSession        => fromCreateSession(cmd)
      case cmd: ConnectionSession.Connecting           => fromConnecting(cmd)
      case cmd: ConnectionSession.Closing              => fromClosing(cmd)
      case cmd: ConnectionSession.OnGet                => fromOnGet(cmd)
      case cmd: ConnectionSession.OnPost               => fromOnPost(cmd)
      case cmd: ConnectionSession.OnFrame              => fromOnFrame(cmd)
      case cmd: ConnectionSession.SendMessage          => fromSendMessage(cmd)
      case cmd: ConnectionSession.SendJson             => fromSendJson(cmd)
      case cmd: ConnectionSession.SendEvent            => fromSendEvent(cmd)
      case cmd: ConnectionSession.SendPackets          => fromSendPackets(cmd)
      case cmd: ConnectionSession.SendAck              => fromSendAck(cmd)
      case cmd: ConnectionSession.SubscribeBroadcast   => fromSubscribeBroadcast(cmd)
      case cmd: ConnectionSession.UnsubscribeBroadcast => fromUnsubscribeBroadcast(cmd)
      case cmd: ConnectionSession.AskStatus            => fromAskStatus(cmd)
      case cmd: ConnectionSession.Broadcast            => fromBroadcast(cmd)
    }
  }

  final def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]): AnyRef = {
    manifest match {
      case Some(clazz) => fromBinaryMap.get(clazz.asInstanceOf[Class[ConnectionSession.Command]]) match {
        case Some(f) => f(bytes)
        case None    => throw new IllegalArgumentException(s"Unimplemented deserialization of message class $clazz in ConnectionCommandSerializer")
      }
      case _ => throw new IllegalArgumentException("Need a command message class to be able to deserialize bytes in ConnectionCommandSerializer")
    }
  }

  final def fromCreateSession(cmd: ConnectionSession.CreateSession) = cmd.sessionId.getBytes

  final def toCreateSession(bytes: Array[Byte]) = ConnectionSession.CreateSession(new String(bytes))

  final def fromConnecting(cmd: ConnectionSession.Connecting) = {
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

    ConnectionSession.Connecting(sessionId, query, origins.toList, tranportConnection, transport)
  }

  final def fromClosing(cmd: ConnectionSession.Closing) = {
    val builder = ByteString.newBuilder

    StringSerializer.appendToBuilder(builder, cmd.sessionId)
    StringSerializer.appendToBuilder(builder, Serialization.serializedActorPath(cmd.transportConnection))

    builder.result.toArray
  }

  final def toClosing(bytes: Array[Byte]) = {
    val data = ByteString(bytes).iterator

    val sessionId = StringSerializer.fromByteIterator(data)
    val ref = system.provider.resolveActorRef(StringSerializer.fromByteIterator(data))

    ConnectionSession.Closing(sessionId, ref)
  }

  final def fromOnGet(cmd: ConnectionSession.OnGet) = {
    val builder = ByteString.newBuilder

    StringSerializer.appendToBuilder(builder, cmd.sessionId)
    StringSerializer.appendToBuilder(builder, Serialization.serializedActorPath(cmd.transportConnection))

    builder.result.toArray
  }

  final def toOnGet(bytes: Array[Byte]) = {
    val data = ByteString(bytes).iterator

    val sessionId = StringSerializer.fromByteIterator(data)
    val ref = system.provider.resolveActorRef(StringSerializer.fromByteIterator(data))

    ConnectionSession.OnGet(sessionId, ref)
  }

  final def fromOnPost(cmd: ConnectionSession.OnPost) = {
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

    ConnectionSession.OnPost(sessionId, ref, payload)
  }

  final def fromOnFrame(cmd: ConnectionSession.OnFrame) = {
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
    ConnectionSession.OnFrame(sessionId, ByteString(payload))
  }

  final def fromSendMessage(cmd: ConnectionSession.SendMessage) = {
    val builder = ByteString.newBuilder

    StringSerializer.appendToBuilder(builder, cmd.sessionId)
    StringSerializer.appendToBuilder(builder, cmd.endpoint)
    StringSerializer.appendToBuilder(builder, cmd.msg)

    builder.result.toArray
  }

  final def toSendMessage(bytes: Array[Byte]) = {
    val data = ByteString(bytes).iterator

    ConnectionSession.SendMessage(StringSerializer.fromByteIterator(data), StringSerializer.fromByteIterator(data), StringSerializer.fromByteIterator(data))
  }

  final def fromSendJson(cmd: ConnectionSession.SendJson) = {
    val builder = ByteString.newBuilder

    StringSerializer.appendToBuilder(builder, cmd.sessionId)
    StringSerializer.appendToBuilder(builder, cmd.endpoint)
    StringSerializer.appendToBuilder(builder, cmd.json)

    builder.result.toArray
  }

  final def toSendJson(bytes: Array[Byte]) = {
    val data = ByteString(bytes).iterator

    ConnectionSession.SendJson(StringSerializer.fromByteIterator(data), StringSerializer.fromByteIterator(data), StringSerializer.fromByteIterator(data))
  }

  final def fromSendEvent(cmd: ConnectionSession.SendEvent) = {
    val builder = ByteString.newBuilder

    StringSerializer.appendToBuilder(builder, cmd.sessionId)
    StringSerializer.appendToBuilder(builder, cmd.endpoint)
    StringSerializer.appendToBuilder(builder, cmd.name)

    cmd.args match {
      case Left(s) =>
        builder.putByte(0x00)
        StringSerializer.appendToBuilder(builder, s)
      case Right(s) =>
        builder.putByte(0x01)
        s.foreach(StringSerializer.appendToBuilder(builder, _))
    }

    builder.result.toArray
  }

  final def toSendEvent(bytes: Array[Byte]) = {
    val data = ByteString(bytes).iterator

    val sessionId = StringSerializer.fromByteIterator(data)
    val endpoint = StringSerializer.fromByteIterator(data)
    val name = StringSerializer.fromByteIterator(data)
    val lr = data.getByte

    val sargs = mutable.ListBuffer[String]()
    while (data.nonEmpty) {
      sargs.append(StringSerializer.fromByteIterator(data))
    }
    val args: Either[String, Seq[String]] = lr match {
      case 0x00 if sargs.nonEmpty => Left[String, Seq[String]](sargs(0))
      case 0x01                   => Right[String, Seq[String]](sargs.toList)
      case _                      => Left[String, Seq[String]]("[]")
    }

    ConnectionSession.SendEvent(sessionId, endpoint, name, args)
  }

  final def fromSendPackets(cmd: ConnectionSession.SendPackets) = {
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

    ConnectionSession.SendPackets(sessionId, packets.toList)
  }

  final def fromSendAck(cmd: ConnectionSession.SendAck) = {
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

    ConnectionSession.SendAck(sessionId, packet, args)
  }

  final def fromSubscribeBroadcast(cmd: ConnectionSession.SubscribeBroadcast) = {
    val builder = ByteString.newBuilder

    StringSerializer.appendToBuilder(builder, cmd.sessionId)
    StringSerializer.appendToBuilder(builder, cmd.endpoint)
    StringSerializer.appendToBuilder(builder, cmd.room)

    builder.result.toArray
  }

  final def toSubscribeBroadcast(bytes: Array[Byte]) = {
    val data = ByteString(bytes).iterator

    ConnectionSession.SubscribeBroadcast(StringSerializer.fromByteIterator(data), StringSerializer.fromByteIterator(data), StringSerializer.fromByteIterator(data))
  }

  final def fromUnsubscribeBroadcast(cmd: ConnectionSession.UnsubscribeBroadcast) = {
    val builder = ByteString.newBuilder

    StringSerializer.appendToBuilder(builder, cmd.sessionId)
    StringSerializer.appendToBuilder(builder, cmd.endpoint)
    StringSerializer.appendToBuilder(builder, cmd.room)

    builder.result.toArray
  }

  final def toUnsubscribeBroadcast(bytes: Array[Byte]) = {
    val data = ByteString(bytes).iterator

    ConnectionSession.UnsubscribeBroadcast(StringSerializer.fromByteIterator(data), StringSerializer.fromByteIterator(data), StringSerializer.fromByteIterator(data))
  }

  final def fromAskStatus(cmd: ConnectionSession.AskStatus) = {
    val builder = ByteString.newBuilder
    StringSerializer.appendToBuilder(builder, cmd.sessionId)
    builder.result.toArray
  }

  final def toAskStatus(bytes: Array[Byte]) = {
    val data = ByteString(bytes).iterator
    val sessionId = StringSerializer.fromByteIterator(data)
    ConnectionSession.AskStatus(sessionId)
  }

  final def fromBroadcast(cmd: ConnectionSession.Broadcast) = {
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

    ConnectionSession.Broadcast(sessionId, room, packet)
  }

}

class StatusSerializer extends Serializer {
  implicit val byteOrder = ByteOrder.BIG_ENDIAN

  final def includeManifest: Boolean = false

  final def identifier: Int = 2006

  final def toBinary(o: AnyRef): Array[Byte] = {
    o match {
      case ConnectionSession.Status(sessionId, connectionTime, location) =>
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

    ConnectionSession.Status(sessionId, connectionTime, location)
  }
}

class NamespaceEventSerializer(val system: ExtendedActorSystem) extends Serializer {
  implicit val byteOrder = ByteOrder.BIG_ENDIAN

  final def includeManifest: Boolean = true

  final def identifier: Int = 2007

  private val fromBinaryMap = collection.immutable.HashMap[Class[_ <: Namespace.Event], Array[Byte] => AnyRef](
    classOf[Namespace.TopicCreated] -> (bytes => toTopicCreated(bytes)))
  //classOf[Namespace.Unsubscribe] -> (bytes => toUnsubscribe(bytes)),
  //classOf[Namespace.SubscribeAck] -> (bytes => toSubscribeAck(bytes)),
  //classOf[Namespace.UnsubscribeAck] -> (bytes => toUnsubscribeAck(bytes)))

  final def toBinary(o: AnyRef): Array[Byte] = {
    o match {
      case cmd: Namespace.TopicCreated => fromTopicCreated(cmd)
      //case cmd: Namespace.Unsubscribe    => fromUnsubscribe(cmd)
      //case cmd: Namespace.SubscribeAck   => fromSubscribeAck(cmd)
      //case cmd: Namespace.UnsubscribeAck => fromUnsubscribeAck(cmd)
    }
  }

  final def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]): AnyRef = {
    manifest match {
      case Some(clazz) => fromBinaryMap.get(clazz.asInstanceOf[Class[Namespace.Event]]) match {
        case Some(f) => f(bytes)
        case None    => throw new IllegalArgumentException(s"Unimplemented deserialization of message class $clazz in NamespaceCommandSerializer")
      }
      case _ => throw new IllegalArgumentException("Need a command message class to be able to deserialize bytes in NamespaceCommandSerializer")
    }
  }

  final def fromTopicCreated(cmd: Namespace.TopicCreated) = {
    val builder = ByteString.newBuilder

    StringSerializer.appendToBuilder(builder, cmd.topic)
    StringSerializer.appendToBuilder(builder, cmd.createdTopic)

    builder.result.toArray
  }

  final def toTopicCreated(bytes: Array[Byte]) = {
    val data = ByteString(bytes).iterator

    val topic = StringSerializer.fromByteIterator(data)
    val createdTopic = StringSerializer.fromByteIterator(data)

    Namespace.TopicCreated(topic, createdTopic)
  }

  //  final def fromUnsubscribe(cmd: Namespace.Unsubscribe) = {
  //    val builder = ByteString.newBuilder
  //
  //    StringSerializer.appendToBuilder(builder, cmd.endpoint)
  //    cmd.channel match {
  //      case Some(ref) =>
  //        builder.putByte(0x00)
  //        StringSerializer.appendToBuilder(builder, Serialization.serializedActorPath(ref))
  //      case None =>
  //        builder.putByte(0x01)
  //    }
  //
  //    builder.result.toArray
  //  }
  //
  //  final def toUnsubscribe(bytes: Array[Byte]) = {
  //    val data = ByteString(bytes).iterator
  //
  //    val endpoint = StringSerializer.fromByteIterator(data)
  //    val channel = data.getByte match {
  //      case 0x00 => Some(system.provider.resolveActorRef(StringSerializer.fromByteIterator(data)))
  //      case 0x01 => None
  //    }
  //
  //    Namespace.Unsubscribe(endpoint, channel)
  //  }
  //
  //  final def fromSubscribeAck(cmd: Namespace.SubscribeAck) = {
  //    fromSubscribe(cmd.subscribe)
  //  }
  //
  //  final def toSubscribeAck(bytes: Array[Byte]) = {
  //    Namespace.SubscribeAck(toSubscribe(bytes))
  //  }
  //
  //  final def fromUnsubscribeAck(cmd: Namespace.UnsubscribeAck) = {
  //    fromUnsubscribe(cmd.unsubscribe)
  //  }
  //
  //  final def toUnsubscribeAck(bytes: Array[Byte]) = {
  //    Namespace.UnsubscribeAck(toUnsubscribe(bytes))
  //  }

}

class OnPacketSerializer extends Serializer {
  implicit val byteOrder = ByteOrder.BIG_ENDIAN

  final def includeManifest: Boolean = true

  final def identifier: Int = 2008

  private val fromBinaryMap = collection.immutable.HashMap[Class[_ <: ConnectionSession.OnPacket], Array[Byte] => AnyRef](
    classOf[ConnectionSession.OnConnect] -> (bytes => toOnConnect(bytes)),
    classOf[ConnectionSession.OnDisconnect] -> (bytes => toOnDisconnect(bytes)),
    classOf[ConnectionSession.OnMessage] -> (bytes => toOnMessage(bytes)),
    classOf[ConnectionSession.OnJson] -> (bytes => toOnJson(bytes)),
    classOf[ConnectionSession.OnEvent] -> (bytes => toOnEvent(bytes)),
    classOf[ConnectionSession.OnNoop] -> (bytes => toOnNoop(bytes)))

  final def toBinary(o: AnyRef): Array[Byte] = {
    o match {
      case cmd: ConnectionSession.OnConnect    => fromOnConnect(cmd)
      case cmd: ConnectionSession.OnDisconnect => fromOnDisconnect(cmd)
      case cmd: ConnectionSession.OnMessage    => fromOnMessage(cmd)
      case cmd: ConnectionSession.OnJson       => fromOnJson(cmd)
      case cmd: ConnectionSession.OnEvent      => fromOnEvent(cmd)
      case cmd: ConnectionSession.OnNoop       => fromOnNoop(cmd)
    }
  }

  final def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]): AnyRef = {
    manifest match {
      case Some(clazz) => fromBinaryMap.get(clazz.asInstanceOf[Class[ConnectionSession.OnPacket]]) match {
        case Some(f) => f(bytes)
        case None    => throw new IllegalArgumentException(s"Unimplemented deserialization of message class $clazz in OnPacketSerializer")
      }
      case _ => throw new IllegalArgumentException("Need a OnPacket message class to be able to deserialize bytes in OnPacketSerializer")
    }
  }

  final def fromOnConnect(o: ConnectionSession.OnConnect): Array[Byte] = {
    val builder = ByteString.newBuilder

    PacketSerializer.appendToBuilder(builder, o.packet)
    ConnectionContextSerializer.appendToBuilder(builder, o.context)
    o.args.foreach { arg =>
      StringSerializer.appendToBuilder(builder, arg._1)
      StringSerializer.appendToBuilder(builder, arg._2)
    }

    builder.result.toArray
  }

  final def toOnConnect(bytes: Array[Byte]): AnyRef = {
    val data = ByteString(bytes).iterator

    val packet = PacketSerializer.fromByteIterator(data).asInstanceOf[ConnectPacket]
    val ctx = ConnectionContextSerializer.fromByteIterator(data)
    val args = mutable.ListBuffer[(String, String)]()
    while (data.nonEmpty) {
      args.append((StringSerializer.fromByteIterator(data), StringSerializer.fromByteIterator(data)))
    }

    ConnectionSession.OnConnect(args, ctx)(packet)
  }

  final def fromOnDisconnect(o: ConnectionSession.OnDisconnect): Array[Byte] = {
    val builder = ByteString.newBuilder

    PacketSerializer.appendToBuilder(builder, o.packet)
    ConnectionContextSerializer.appendToBuilder(builder, o.context)

    builder.result.toArray
  }

  final def toOnDisconnect(bytes: Array[Byte]): AnyRef = {
    val data = ByteString(bytes).iterator

    val packet = PacketSerializer.fromByteIterator(data).asInstanceOf[DisconnectPacket]
    val ctx = ConnectionContextSerializer.fromByteIterator(data)

    ConnectionSession.OnDisconnect(ctx)(packet)
  }

  final def fromOnMessage(o: ConnectionSession.OnMessage): Array[Byte] = {
    val builder = ByteString.newBuilder

    PacketSerializer.appendToBuilder(builder, o.packet)
    ConnectionContextSerializer.appendToBuilder(builder, o.context)
    StringSerializer.appendToBuilder(builder, o.msg)

    builder.result.toArray
  }

  final def toOnMessage(bytes: Array[Byte]): AnyRef = {
    val data = ByteString(bytes).iterator

    val packet = PacketSerializer.fromByteIterator(data).asInstanceOf[MessagePacket]
    val ctx = ConnectionContextSerializer.fromByteIterator(data)
    val msg = StringSerializer.fromByteIterator(data)

    ConnectionSession.OnMessage(msg, ctx)(packet)
  }

  final def fromOnJson(o: ConnectionSession.OnJson): Array[Byte] = {
    val builder = ByteString.newBuilder

    PacketSerializer.appendToBuilder(builder, o.packet)
    ConnectionContextSerializer.appendToBuilder(builder, o.context)
    StringSerializer.appendToBuilder(builder, o.json)

    builder.result.toArray
  }

  final def toOnJson(bytes: Array[Byte]): AnyRef = {
    val data = ByteString(bytes).iterator

    val packet = PacketSerializer.fromByteIterator(data).asInstanceOf[JsonPacket]
    val ctx = ConnectionContextSerializer.fromByteIterator(data)
    val json = StringSerializer.fromByteIterator(data)

    ConnectionSession.OnJson(json, ctx)(packet)
  }

  final def fromOnEvent(o: ConnectionSession.OnEvent): Array[Byte] = {
    val builder = ByteString.newBuilder

    PacketSerializer.appendToBuilder(builder, o.packet)
    ConnectionContextSerializer.appendToBuilder(builder, o.context)
    StringSerializer.appendToBuilder(builder, o.name)
    StringSerializer.appendToBuilder(builder, o.args)

    builder.result.toArray
  }

  final def toOnEvent(bytes: Array[Byte]): AnyRef = {
    val data = ByteString(bytes).iterator

    val packet = PacketSerializer.fromByteIterator(data).asInstanceOf[EventPacket]
    val ctx = ConnectionContextSerializer.fromByteIterator(data)
    val name = StringSerializer.fromByteIterator(data)
    val args = StringSerializer.fromByteIterator(data)

    ConnectionSession.OnEvent(name, args, ctx)(packet)
  }

  final def fromOnNoop(o: ConnectionSession.OnNoop): Array[Byte] = {
    val builder = ByteString.newBuilder

    ConnectionContextSerializer.appendToBuilder(builder, o.context)

    builder.result.toArray
  }

  final def toOnNoop(bytes: Array[Byte]): AnyRef = {
    val data = ByteString(bytes).iterator

    val ctx = ConnectionContextSerializer.fromByteIterator(data)

    ConnectionSession.OnNoop(ctx)(NoopPacket)
  }

}
