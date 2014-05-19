package spray.contrib.socketio.serializer

import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpecLike }
import akka.actor.ActorSystem
import akka.testkit.{ ImplicitSender, TestKit }
import akka.serialization.SerializationExtension
import com.typesafe.config.ConfigFactory
import spray.contrib.socketio.ConnectionActive._
import spray.contrib.socketio.ConnectionContext
import spray.contrib.socketio.packet.{ Packet, MessagePacket }
import spray.can.websocket.frame.TextFrame
import spray.contrib.socketio.transport
import spray.http.Uri.Query
import spray.http.HttpOrigin
import spray.contrib.socketio.transport.WebSocket
import spray.contrib.socketio.ConnectionActive.OnFrame
import spray.contrib.socketio.ConnectionActive.OnGet
import spray.contrib.socketio.ConnectionActive.CreateSession
import spray.contrib.socketio.ConnectionActive.Connecting
import scala.Some
import akka.util.ByteString

class SerializerSpec(_system: ActorSystem) extends TestKit(_system) with ImplicitSender with WordSpecLike with Matchers with BeforeAndAfterAll {

  def this() = this(ActorSystem("MySpec", ConfigFactory.parseString("""
akka {
  actor {
    serializers {
      frame = "spray.contrib.socketio.serializer.FrameSerializer"
      command = "spray.contrib.socketio.serializer.CommandSerializer"
      event = "spray.contrib.socketio.serializer.EventSerializer"
      packet = "spray.contrib.socketio.serializer.PacketSerializer"
      connctx = "spray.contrib.socketio.serializer.ConnectionContextSerializer"
      onpacket = "spray.contrib.socketio.serializer.OnPacketSerializer"
      onbroadcast = "spray.contrib.socketio.serializer.OnBroadcastSerializer"
    }
    serialization-bindings {
      "spray.can.websocket.frame.Frame" = frame
      "spray.contrib.socketio.ConnectionActive$Command" = command
      "spray.contrib.socketio.ConnectionActive$Event" = event
      "spray.contrib.socketio.packet.Packet" = packet
      "spray.contrib.socketio.ConnectionContext" = connctx
      "spray.contrib.socketio.ConnectionActive$OnPacket" = onpacket
      "spray.contrib.socketio.ConnectionActive$OnBroadcast" = onbroadcast
    }
  }
}
""")))

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  def test(obj: AnyRef) = {
    val serialization = SerializationExtension(system)
    val serializer = serialization.findSerializerFor(obj)
    val bytes = serializer.toBinary(obj)
    val back = serializer.fromBinary(bytes, if (serializer.includeManifest) Some(obj.getClass) else None)
    assertResult(obj)(back)
  }

  val sessionId = "138129031209-DASDASLJDLKAS-DASd1938219381"
  val query = Query("a=1&b=2")
  val origins = List(HttpOrigin("http://www.google.com"))
  val packet = MessagePacket(-1, false, "", "hello world")

  "Serializer" must {
    "handle Frame" in {
      val obj = TextFrame("hello world")
      test(obj)
    }

    "handle Packet" in {
      test(packet)
    }

    "handle ConnectionContext" in {
      val obj = new ConnectionContext(sessionId, query, origins)
      obj.bindTransport(transport.WebSocket)
      test(obj)
    }

    "handle Command" when {
      "CreateSession" in {
        val obj = CreateSession(sessionId)
        test(obj)
      }

      "Connecting" in {
        val obj = Connecting(sessionId, query, origins, self, WebSocket)
        test(obj)
      }

      "Closing" in {
        val obj = Closing(sessionId)
        test(obj)
      }

      "OnGet" in {
        val obj = OnGet(sessionId, self)
        test(obj)
      }

      "OnPost" in {
        val obj = OnPost(sessionId, self, ByteString("hello world"))
        test(obj)
      }

      "OnFrame" in {
        val obj = OnFrame(sessionId, ByteString("hello world"))
        test(obj)
      }

      "SendMessage" in {
        val obj = SendMessage(sessionId, "chat", "hello world")
        test(obj)
      }

      "SendJson" in {
        val obj = SendJson(sessionId, "chat", "[1,2,3]")
        test(obj)
      }

      "SendEvent" in {
        val obj1 = SendEvent(sessionId, "chat", "string", Left("hello world"))
        test(obj1)

        val obj2 = SendEvent(sessionId, "chat", "list", Right(List[String]("hello", "world")))
        test(obj2)

        val obj3 = SendEvent(sessionId, "chat", "list", Right(List[String]("hello")))
        test(obj3)
      }

      "SendPackets" in {
        val obj = SendPackets(sessionId, List[Packet](packet, packet.copy(data = "hello world2")))
        test(obj)
      }

      "SendAck" in {
        val obj = SendAck(sessionId, packet, "args")
        test(obj)
      }

      "SubscribeBroadcast" in {
        val obj = SubscribeBroadcast(sessionId, "chat", "room1")
        test(obj)
      }

      "UnsubscribeBroadcast" in {
        val obj = UnsubscribeBroadcast(sessionId, "chat", "room1")
        test(obj)
      }

      "Broadcast" in {
        val obj = Broadcast(sessionId, "room1", packet)
        test(obj)
      }
    }

    "handle Event" when {
      "ConnectingEvent" in {
        val obj = ConnectingEvent(sessionId, query, origins, self, WebSocket)
        test(obj)
      }

      "SubscribeBroadcastEvent" in {
        val obj = SubscribeBroadcastEvent(sessionId, "chat", "room1")
        test(obj)
      }

      "UnsubscribeBroadcastEvent" in {
        val obj = UnsubscribeBroadcastEvent(sessionId, "chat", "room1")
        test(obj)
      }
    }
  }
}
