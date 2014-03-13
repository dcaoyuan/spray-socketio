package spray.contrib.socketio.examples.benchmark

import akka.actor.ActorRef
import akka.io.IO
import spray.can.Http
import spray.can.server.UHttp
import spray.can.websocket
import spray.can.websocket.frame.TextFrame
import spray.contrib.socketio
import spray.contrib.socketio.examples.benchmark.SocketIOLoadTester.MessageArrived
import spray.contrib.socketio.examples.benchmark.SocketIOLoadTester.OnClose
import spray.contrib.socketio.examples.benchmark.SocketIOLoadTester.OnOpen
import spray.contrib.socketio.packet.{MessagePacket, EventPacket, Packet}
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
  case object SendTimestampedChat
  case object SendHello
  case class SendBroadcast(msg: String)
}

class SocketIOTestClient(connect: Http.Connect, report: ActorRef) extends socketio.SocketIOClientConnection {
  val Id = SocketIOTestClient.nextId.toString

  import context.system
  IO(UHttp) ! connect

  def businessLogic: Receive = {
    case SocketIOTestClient.SendHello           => connection ! TextFrame("5:::{\"name\":\"hello\", \"args\":[]}")
    case SocketIOTestClient.SendTimestampedChat => connection ! TextFrame(timestampedChat)
    case SocketIOTestClient.SendBroadcast(msg) => connection ! TextFrame( """5:::{"name":"broadcast", "args":""" + spray.json.JsString(msg).value + "}")
  }

  override def onDisconnected(endpoint: String) {
    report ! OnClose
  }

  override def onOpen() {
    report ! OnOpen
  }

  def onPacket(packet: Packet) {
    val messageArrivedAt = System.currentTimeMillis
    packet match {
      case EventPacket(name, args) =>
        JsonParser(args) match {
          case JsArray(xs) =>
            xs.headOption match {
              case Some(JsObject(fields)) =>
                fields.get("text") match {
                  case Some(JsString(message)) =>
                    message.split(",") match {
                      case Array(Id, timestamp) =>
                        val roundtripTime = messageArrivedAt - timestamp.toLong
                        log.debug("roundtripTime {}", roundtripTime)
                        report ! MessageArrived(roundtripTime)
                      case _ =>
                    }
                  case _ =>
                }
              case _ =>
            }
          case _ =>
        }
      case msg: MessagePacket => report ! msg.data
      case _ =>
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
