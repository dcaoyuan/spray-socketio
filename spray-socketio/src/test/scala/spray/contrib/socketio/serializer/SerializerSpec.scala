package spray.contrib.socketio.serializer

import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpecLike }
import akka.actor.Actor
import akka.actor.ActorSystem
import akka.testkit.{ ImplicitSender, TestKit }
import akka.actor.Props
import akka.serialization.SerializationExtension
import akka.util.ByteString
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
import scala.collection.immutable

class SerializerSpec(_system: ActorSystem) extends TestKit(_system) with ImplicitSender with WordSpecLike with Matchers with BeforeAndAfterAll {

  def this() = this(ActorSystem("MySpec", ConfigFactory.parseString("""
akka {
  actor {
    serializers {
      frame = "spray.contrib.socketio.serializer.FrameSerializer"
      packet = "spray.contrib.socketio.serializer.PacketSerializer"
      connctx = "spray.contrib.socketio.serializer.ConnectionContextSerializer"
      state = "spray.contrib.socketio.serializer.ConnectionActiveStateSerializer"
      command = "spray.contrib.socketio.serializer.CommandSerializer"
      onpacket = "spray.contrib.socketio.serializer.OnPacketSerializer"
      onbroadcast = "spray.contrib.socketio.serializer.OnBroadcastSerializer"
      status = "spray.contrib.socketio.serializer.StatusSerializer"
    }
    serialization-bindings {
      "spray.can.websocket.frame.Frame" = frame
      "spray.contrib.socketio.packet.Packet" = packet
      "spray.contrib.socketio.ConnectionContext" = connctx
      "spray.contrib.socketio.ConnectionActive$State" = state
      "spray.contrib.socketio.ConnectionActive$Command" = command
      "spray.contrib.socketio.ConnectionActive$OnPacket" = onpacket
      "spray.contrib.socketio.ConnectionActive$OnBroadcast" = onbroadcast
      "spray.contrib.socketio.ConnectionActive$Status" = status
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
    val res = serialization.deserialize(bytes, obj.getClass).get
    assertResult(obj)(res)

    val resById = serialization.deserialize(bytes, serializer.identifier, Some(obj.getClass)).get
    assertResult(obj)(resById)
  }

  class Test_Actor extends Actor {
    def receive: Receive = {
      case "test" =>
    }
  }
  val sessionId = "138129031209-DASDASLJDLKAS-DASd1938219381"
  val query = Query("a=1&b=2")
  val origins = List(HttpOrigin("http://www.google.com"))
  val packet = MessagePacket(-1, false, "", "hello world")
  // If you want to make system.actorOf with nested classes, you will need to instantiate the 
  // nested actor passing in a reference to the enclosing instance as a constructor arg.
  val testActorRef = system.actorOf(Props(classOf[Test_Actor], this))
  val deadleaters = system.deadLetters

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
      obj.transport = transport.WebSocket
      obj.isConnected = true
      test(obj)
    }

    "handle ConnectionActiveState with actorRef" in {
      val ctx = new ConnectionContext(sessionId, query, origins)
      ctx.transport = transport.WebSocket
      ctx.isConnected = true
      val obj = new State(ctx, testActorRef, immutable.Set("topic1, topic2"))
      test(obj)
    }

    "handle ConnectionActiveState with deadletters" in {
      val ctx = new ConnectionContext(sessionId, query, origins)
      ctx.transport = transport.WebSocket
      ctx.isConnected = true
      val obj = new State(ctx, system.deadLetters, immutable.Set("topic1, topic2"))
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
        val obj = Closing(sessionId, self)
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

      "GetStatus" in {
        val obj = GetStatus(sessionId)
        test(obj)
      }

      "Broadcast" in {
        val obj = Broadcast(sessionId, "room1", packet)
        test(obj)
      }
    }

    "handle Status" when {
      "has sessionId" in {
        val obj = Status(sessionId, 10000L, self.path.toSerializationFormat)
        test(obj)
      }
      "null sessionId" in {
        val obj = Status(null, 10000L, null)
        test(obj)
      }
    }
  }
}
