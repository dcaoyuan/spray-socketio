package spray.contrib.socketio.packet

object Packet {
  val SEPARATOR = ':'.toByte
  val PLUS = '+'.toByte

  val eventReservedNames = Set(
    "message",
    "connect",
    "disconnect",
    "open",
    "close",
    "error",
    "retry",
    "reconnect")
}

sealed trait Packet {
  def code: Byte
  def id: Long
  def endpoint: String
  def isAck: Boolean
}

final case class DisconnectPacket(endpoint: String) extends Packet {
  def code = '0'
  def id = -1L
  def isAck = false
}

final case class ConnectPacket(endpoint: String = "", args: Seq[(String, String)] = Nil) extends Packet {
  def code = '1'
  def id = -1L
  def isAck = false
}

final case class MessagePacket(id: Long, isAck: Boolean, endpoint: String, data: String) extends Packet {
  def code = '2'
}

final case class JsonMessagePacket(id: Long, isAck: Boolean, endpoint: String, data: String) extends Packet {
  def code = '3'
}

final case class EventPacket(id: Long, isAck: Boolean, endpoint: String, data: String) extends Packet {
  def code = '4'
}

final case class AckPacket(ackId: Long, data: String) extends Packet {
  def code = '5'
  def id = -1L
  def endpoint = ""
  def isAck = false
}

final case class ErrorPacket(endpoint: String, reason: String, advice: String = "") extends Packet {
  def code = '6'
  def id = -1L
  def isAck = false
}

case object HeartbeatPacket extends Packet {
  def code = '7'
  def id = -1L
  def endpoint = ""
  def isAck = false
}

case object NoopPacket extends Packet {
  def code = '8'
  def id = -1L
  def endpoint = ""
  def isAck = false
}

