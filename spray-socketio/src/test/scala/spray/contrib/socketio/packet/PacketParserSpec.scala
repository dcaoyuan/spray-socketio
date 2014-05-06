package spray.contrib.socketio.packet

import org.scalatest.{ Matchers, BeforeAndAfterAll, WordSpecLike }
import scala.util.Success
import akka.util.ByteString

class PacketParserSpec extends WordSpecLike with Matchers with BeforeAndAfterAll {

  "A PacketParser" when {

    "parse DisconnectPacket" should {
      "handle simple packet" in {
        assertResult(Success(Seq(DisconnectPacket())))(PacketParser("0"))
      }

      "handle packet with endpoint" in {
        assertResult(Success(Seq(DisconnectPacket("endpoint"))))(PacketParser("0::/endpoint"))
      }
    }

    "parse ConnectPacket" should {
      "handle packet with endpoint" in {
        assertResult(Success(Seq(ConnectPacket("test"))))(PacketParser("1::/test"))
      }

      "handle packet with arg" in {
        assertResult(Success(Seq(ConnectPacket("test", Seq(("arg1", "1"))))))(PacketParser("1::/test?arg1=1"))
      }

      "handle packet with args" in {
        assertResult(Success(Seq(ConnectPacket("test", Seq(("arg1", "1"), ("arg2", "2"))))))(PacketParser("1::/test?arg1=1&arg2=2"))
      }
    }

    "parse HeartbeatPacket" should {
      "handle simple packet" in {
        assertResult(Success(Seq(HeartbeatPacket)))(PacketParser("2"))
        assertResult(Success(Seq(HeartbeatPacket)))(PacketParser("2:"))
        assertResult(Success(Seq(HeartbeatPacket)))(PacketParser("2::"))
        assertResult(Success(Seq(HeartbeatPacket)))(PacketParser("2:::"))
      }
    }

    "parse MessagePacket" should {
      "handle simple packet" in {
        assertResult(Success(Seq(MessagePacket(-1, false, "", "hello world"))))(PacketParser("3:::hello world"))
      }
    }

    "parse EventPacket" should {
      "handle packet with id and ack" in {
        assertResult(Success(Seq(EventPacket(1, true, "", "tobi", "[]"))))(PacketParser("""5:1+::{"name":"tobi"}"""))
      }

      "handle packet with args" in {
        assertResult(Success(Seq(EventPacket(-1, false, "", "edwald", """[{"a": "b"},2,"3"]"""))))(PacketParser("""5:::{"name":"edwald","args":[{"a": "b"},2,"3"]}"""))
      }

      "handle packet with id, endpoint and args" in {
        assertResult(Success(Seq(EventPacket(21312312, false, "test", "edwald", """[{"a": "b"},2,"3"]"""))))(PacketParser("""5:21312312:test:{"name":"edwald","args":[{"a": "b"},2,"3"]}"""))
      }

      "handle packet with endpoint and args" in {
        assertResult(Success(Seq(EventPacket(-1, false, "testendpoint", "Hi!", "[]"))))(PacketParser("""5::/testendpoint:{"name":"Hi!","args":[]}"""))
      }
    }

    "split name args" should {
      //TODO trim ending space
      "handle simple packet" in {
        assertResult(("edwald", """[{"a": "b"},2,"3"] """))(EventPacket.splitNameArgs(""" { "name" : "edwald", "args" :[{"a": "b"},2,"3"] } """))
      }
      "handle reverse order packet" in {
        assertResult(("edwald", """[{"a": "b"},2,"3"]"""))(EventPacket.splitNameArgs(""" { "args" :[{"a": "b"},2,"3"], "name" : "edwald" } """))
      }
    }

    "parse AckPacket" should {
      "handle simple packet" in {
        assertResult(Success(Seq(AckPacket(140, ""))))(PacketParser("6:::140"))
      }
    }

    "parse UTF-8 string" should {
      "handle simple packet" in {
        assertResult(Success(Seq(ConnectPacket("testendpoint"), ConnectPacket("testendpoint2"))))(PacketParser("""\ufffd16\ufffd1::/testendpoint\ufffd17\ufffd1::/testendpoint2"""))
      }
    }

    "parse ByteString" should {
      "handle simple packet" in {
        assertResult(Success(Seq(EventPacket(-1, false, "", "chat", """[{"text":"281,1394290715166"}]"""), HeartbeatPacket)))(PacketParser(ByteString(-17, -65, -67, 53, 55, -17, -65, -67, 53, 58, 58, 58, 123, 34, 110, 97, 109, 101, 34, 58, 34, 99, 104, 97, 116, 34, 44, 34, 97, 114, 103, 115, 34, 58, 91, 123, 34, 116, 101, 120, 116, 34, 58, 34, 50, 56, 49, 44, 49, 51, 57, 52, 50, 57, 48, 55, 49, 53, 49, 54, 54, 34, 125, 93, 125, -17, -65, -67, 51, -17, -65, -67, 50, 58, 58)))
      }
    }
  }
}
