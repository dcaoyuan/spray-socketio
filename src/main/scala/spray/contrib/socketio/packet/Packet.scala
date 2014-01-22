package spray.contrib.socketio.packet

sealed trait Packet

final case class DisconnectPacket(endpoint: String) extends Packet
final case class ConnectPacket(endpoint: String, args: Seq[(String, String)]) extends Packet
final case class MessagePacket(id: Long, isAck: Boolean, endpoint: String, data: String) extends Packet
final case class JsonMessagePacket(id: Long, isAck: Boolean, endpoint: String, data: String) extends Packet
final case class EventPacket(id: Long, isAck: Boolean, endpoint: String, data: String) extends Packet
final case class AckPacket(id: Long, data: String) extends Packet
final case class ErrorPacket(endpoint: String, reason: String, advice: String = "") extends Packet
case object HeartbeatPacket extends Packet
case object NoopPacket extends Packet

