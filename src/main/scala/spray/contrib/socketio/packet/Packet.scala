package spray.contrib.socketio.packet

import spray.json.JsValue
import spray.json.JsonParser

object Packet {
  val reservedEvents = Set(
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
  def isAckRequested: Boolean
}

/**
 * Signals disconnection. If no endpoint is specified, disconnects the entire socket.
 */
final case class DisconnectPacket(endpoint: String) extends Packet {
  def code = '0'
  def id = -1L
  def isAckRequested = false
}

/**
 * Only used for multiple sockets. Signals a connection to the endpoint. Once the
 * server receives it, it's echoed back to the client.
 */
final case class ConnectPacket(endpoint: String = "", args: Seq[(String, String)] = Nil) extends Packet {
  def code = '1'
  def id = -1L
  def isAckRequested = false
}

/**
 * Sends a heartbeat. Heartbeats must be sent within the interval negotiated with
 * the server. It's up to the client to decide the padding (for example, if the
 * heartbeat timeout negotiated with the server is 20s, the client might want to
 * send a heartbeat evert 15s).
 */
case object HeartbeatPacket extends Packet {
  def code = '2'
  def id = -1L
  def endpoint = ""
  def isAckRequested = false
}

/**
 * A regular message.
 */
final case class MessagePacket(id: Long, isAckRequested: Boolean, endpoint: String, data: String) extends Packet {
  def code = '3'
}

/**
 * A JSON encoded message.
 */
final case class JsonPacket(id: Long, isAckRequested: Boolean, endpoint: String, json: JsValue) extends Packet {
  def code = '4'
}

/**
 * An event is like a json message, but has mandatory name and args fields. name
 * is a string and args an array.
 */
final case class EventPacket(id: Long, isAckRequested: Boolean, endpoint: String, json: JsValue) extends Packet {
  def code = '5'
}

/**
 * An acknowledgment contains the message id as the message data. If a + sign
 * follows the message id, it's treated as an event message packet.
 */
final case class AckPacket(ackId: Long, args: String) extends Packet {
  def code = '6'
  def id = -1L
  def endpoint = ""
  def isAckRequested = false
}

final case class ErrorPacket(endpoint: String, reason: String, advice: String = "") extends Packet {
  def code = '7'
  def id = -1L
  def isAckRequested = false
}

/**
 * No operation. Used for example to close a poll after the polling duration times out.
 */
case object NoopPacket extends Packet {
  def code = '8'
  def id = -1L
  def endpoint = ""
  def isAckRequested = false
}

