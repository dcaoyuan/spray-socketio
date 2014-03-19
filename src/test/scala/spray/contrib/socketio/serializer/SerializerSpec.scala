package spray.contrib.socketio.serializer

import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}
import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestKit}
import akka.serialization.SerializationExtension
import com.typesafe.config.ConfigFactory
import spray.contrib.socketio.packet.MessagePacket
import spray.can.websocket.frame.TextFrame

class SerializerSpec(_system: ActorSystem) extends TestKit(_system) with ImplicitSender with WordSpecLike with Matchers with BeforeAndAfterAll {

  def this() = this(ActorSystem("MySpec", ConfigFactory.parseString( """
  akka {
    actor {
      serializers {
        packet = "spray.contrib.socketio.serializer.PacketSerializer"
        frame = "spray.contrib.socketio.serializer.FrameSerializer"
      }
      serialization-bindings {
        "spray.contrib.socketio.packet.Packet" = packet
        "spray.can.websocket.frame.Frame" = frame
      }
    }
  }
                                                                     """)))

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  "Serializer" must {
    "packet" in {
      val serialization = SerializationExtension(system)
      val packet = MessagePacket(-1, false, "", "hello world")
      val serializer = serialization.findSerializerFor(packet)
      val bytes = serializer.toBinary(packet)
      val back = serializer.fromBinary(bytes, manifest = None)

      assert(back == packet)
    }

    "frame" in {
      val serialization = SerializationExtension(system)
      val frame = TextFrame("hello world")
      val serializer = serialization.findSerializerFor(frame)
      val bytes = serializer.toBinary(frame)
      val back = serializer.fromBinary(bytes, manifest = None)

      assert(back == frame)
    }
  }
}
