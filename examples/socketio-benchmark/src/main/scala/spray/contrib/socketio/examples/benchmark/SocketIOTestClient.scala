package spray.contrib.socketio.examples.benchmark

import akka.actor.Actor
import akka.actor.ActorRef
import akka.io.IO
import spray.can.Http
import spray.can.server.UHttp
import spray.can.websocket
import spray.can.websocket.frame.TextFrame
import spray.contrib.socketio
import spray.contrib.socketio.packet.{ MessagePacket, EventPacket, Packet }
import spray.json.JsonParser
import spray.json.JsArray
import spray.json.JsObject
import spray.json.JsString

object SocketIOTestClient {
  private var _nextId = 0
  private def nextId = {
    _nextId += 1
    _nextId
  }

  val wsHandshakeReq = websocket.basicHandshakeRepuset("/mytest")

  case object OnOpen
  case object OnClose
  case class MessageArrived(roundtrip: Long)

  case object SendTimestampedChat
  case object SendHello
  case class SendBroadcast(msg: String)
}

class SocketIOTestClient(connect: Http.Connect, commander: ActorRef) extends Actor with socketio.SocketIOClientWorker {
  import SocketIOTestClient._

  val Id = nextId.toString

  import context.system
  IO(UHttp) ! connect

  def businessLogic: Receive = {
    case SendHello           => connection ! TextFrame("5:::{\"name\":\"hello\", \"args\":[]}")
    case SendTimestampedChat => connection ! TextFrame(timestampedChat)
    case SendBroadcast(msg)  => connection ! TextFrame("""5:::{"name":"broadcast", "args":[""" + "\"" + msg + "\"" + "]}")
  }

  override def onDisconnected(endpoint: String) {
    commander ! OnClose
  }

  override def onOpen() {
    commander ! OnOpen
  }

  def onPacket(packet: Packet) {
    val messageArrivedAt = System.currentTimeMillis
    packet match {
      case EventPacket("chat", args) =>
        JsonParser(args) match {
          case JsArray(xs) =>
            xs.headOption match {
              case Some(JsObject(fields)) =>
                fields.get("text") match {
                  case Some(JsString(message)) =>
                    message.split(",") match {
                      case Array(id, timestamp) =>
                        val roundtripTime = messageArrivedAt - timestamp.toLong
                        log.debug("roundtripTime {}", roundtripTime)
                        commander ! MessageArrived(roundtripTime)
                      case _ =>
                    }
                  case _ =>
                }
              case _ =>
            }
          case _ =>
        }
      case msg: MessagePacket => commander ! msg.data
      case _                  =>
    }

  }

  def chat(message: String): String = {
    "5:::{\"name\":\"chat\", \"args\":[{\"text\":\"" + message + "\"}]}"
  }

  def timestampedChat = {
    val message = Id + "," + System.currentTimeMillis
    chat(message)
  }

}
