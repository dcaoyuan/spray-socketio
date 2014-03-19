package spray.contrib.socketio.serializer

import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpecLike }
import akka.actor.ActorSystem
import akka.testkit.{ ImplicitSender, TestKit }
import akka.serialization.SerializationExtension
import com.typesafe.config.ConfigFactory
import spray.contrib.socketio.ConnectionActive.OnFrame
import spray.contrib.socketio.ConnectionContext
import spray.contrib.socketio.packet.MessagePacket
import spray.can.websocket.frame.TextFrame
import spray.contrib.socketio.transport

class SerializerSpec(_system: ActorSystem) extends TestKit(_system) with ImplicitSender with WordSpecLike with Matchers with BeforeAndAfterAll {

  def this() = this(ActorSystem("MySpec", ConfigFactory.parseString("""
akka {
  actor {
    serializers {
      frame = "spray.contrib.socketio.serializer.FrameSerializer"
      onframe = "spray.contrib.socketio.serializer.OnFrameSerializer"
      packet = "spray.contrib.socketio.serializer.PacketSerializer"
      connctx = "spray.contrib.socketio.serializer.ConnectionContextSerializer"
      onpacket = "spray.contrib.socketio.serializer.OnPacketSerializer"
    }
    serialization-bindings {
      "spray.can.websocket.frame.Frame" = frame
      "spray.contrib.socketio.ConnectionActive$OnFrame" = onframe
      "spray.contrib.socketio.packet.Packet" = packet
      "spray.contrib.socketio.ConnectionContext" = connctx
      "spray.contrib.socketio.ConnectionActive$OnPacket" = onpacket
    }
  }
}
""")))

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  "Serializer" must {
    "frame" in {
      val serialization = SerializationExtension(system)
      val obj = TextFrame("hello world")
      val serializer = serialization.findSerializerFor(obj)
      val bytes = serializer.toBinary(obj)
      val back = serializer.fromBinary(bytes, manifest = None)

      assert(back == obj)
    }

    "onframe" in {
      val serialization = SerializationExtension(system)
      val obj = OnFrame("138129031209-DASDASLJDLKAS-DASd1938219381", TextFrame("hello world"))
      val serializer = serialization.findSerializerFor(obj)
      val bytes = serializer.toBinary(obj)
      val back = serializer.fromBinary(bytes, manifest = None)

      assert(back == obj)
    }

    "packet" in {
      val serialization = SerializationExtension(system)
      val obj = MessagePacket(-1, false, "", "hello world")
      val serializer = serialization.findSerializerFor(obj)
      val bytes = serializer.toBinary(obj)
      val back = serializer.fromBinary(bytes, manifest = None)

      assert(back == obj)
    }

    "connectionContext" in {
      val serialization = SerializationExtension(system)
      val obj = new ConnectionContext("138129031209-DASDASLJDLKAS-DASd1938219381", null, List())
      obj.bindTransport(transport.WebSocket)
      val serializer = serialization.findSerializerFor(obj)
      val bytes = serializer.toBinary(obj)
      val back = serializer.fromBinary(bytes, manifest = None)

      assert(back == obj)
    }
  }
}
