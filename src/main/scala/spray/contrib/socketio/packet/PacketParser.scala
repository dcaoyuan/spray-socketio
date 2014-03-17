package spray.contrib.socketio.packet

import akka.util.ByteString
import org.parboiled2._
import scala.collection.immutable.Queue
import scala.util.Failure
import scala.util.Success

class PacketParser(val input: ParserInput) extends Parser with StringBuilding {

  def Packets = rule {
    (oneOrMore(DelimitedPacket) | { Packet ~> (List(_)) /* ~ zeroOrMore(DelimitedPacket) ~> (_ +: _) */ }) ~ EOI
  }

  def DelimitedPacket =
    rule { "\ufffd" ~ LongValue ~ "\ufffd" ~ Packet ~> { (len, packet) => packet } }

  def Packet: Rule1[Packet] = rule {
    run {
      (cursorChar: @scala.annotation.switch) match {
        case '0' => Disconnect
        case '1' => Connect
        case '2' => Heartbeat
        case '3' => Message
        case '4' => JsonMessage
        case '5' => Event
        case '6' => Ack
        case '7' => Error
        case '8' => Noop
      }
    }
  }

  def Disconnect =
    rule { "0" ~ { optional("::/" ~ Endpoint) ~> (_.getOrElse("")) } ~> DisconnectPacket }
  def Connect =
    rule { "1::" ~ { optional("/" ~ Endpoint) ~> (_.getOrElse("")) } ~ { optional("?" ~ zeroOrMore(Query).separatedBy("&")) ~> (_.getOrElse(Nil)) } ~> ConnectPacket }
  def Heartbeat =
    rule { "2" ~ optional("::") ~ push(HeartbeatPacket) }
  def Message =
    rule { "3:" ~ GenericMessagePre ~ ":" ~ StrData ~> MessagePacket }
  def JsonMessage =
    rule { "4:" ~ GenericMessagePre ~ ":" ~ StrData ~> JsonPacket }
  def Event =
    rule { "5:" ~ GenericMessagePre ~ ":" ~ StrData ~> (EventPacket(_, _, _, _)) }
  def Ack =
    rule { "6:::" ~ MessageId ~ { optional("+" ~ StrData) ~> (_.getOrElse("")) } ~> AckPacket }
  def Error =
    rule { "7::" ~ Endpoint ~ ":" ~ Reason ~ { optional("+" ~ Advice) ~> (_.getOrElse("")) } ~> ErrorPacket }
  def Noop =
    rule { "8" ~ push(NoopPacket) }

  def GenericMessagePre = rule({ optional(MessageId) ~> (_.getOrElse(-1L)) }
    ~ { optional("+" ~ push(true)) ~> (_.getOrElse(false)) } ~ ":"
    ~ { optional(optional("/") ~ Endpoint) ~> (_.getOrElse("")) })

  def StrData = rule { clearSB() ~ zeroOrMore(!anyOf("\ufffd") ~ ANY ~ append()) ~ push(sb.toString) }

  def MessageId = rule { Digits ~> (_.toLong) }

  def Endpoint = rule { Characters ~> (_.toString) }

  def Query = rule { ParamLable ~ "=" ~ ParamValue ~> ((_, _)) }
  def ParamLable = rule { Characters ~> (_.toString) }
  def ParamValue = rule { Characters ~> (_.toString) }

  def Reason = rule { Characters ~> (_.toString) }
  def Advice = rule { Characters ~> (_.toString) }

  def LongValue = rule { Digits ~> (_.toLong) }

  def Digit = rule { "0" - "9" }

  def Digits = rule { capture(oneOrMore(Digit)) }

  def HexDigit = rule { "0" - "9" | "a" - "f" | "A" - "F" }

  def Characters = rule { capture(zeroOrMore("\\" ~ EscapedChar | NormalChar)) }

  def EscapedChar = rule(
    anyOf("\"\\/") ~ append()
      | "b" ~ append('\b')
      | "f" ~ append('\f')
      | "n" ~ append('\n')
      | "r" ~ append('\r')
      | "t" ~ append('\t')
      | Unicode ~> { code => sb.append(code.asInstanceOf[Char]); () })

  def NormalChar = rule { !anyOf("\ufffd:?=&\"\\") ~ ANY ~ append() }

  def Unicode = rule { 'u' ~ capture(HexDigit ~ HexDigit ~ HexDigit ~ HexDigit) ~> (java.lang.Integer.parseInt(_, 16)) }
}

object PacketParser {
  def apply(input: ByteString): Seq[Packet] = apply(input.utf8String)
  def apply(input: String): Seq[Packet] = apply(input.toCharArray)
  def apply(input: Array[Char]): Seq[Packet] = {
    val parser = new PacketParser(input)
    parser.Packets.run() match {
      case Success(packets)        => packets
      case Failure(ex: ParseError) => throw ex
      case Failure(ex)             => throw ex
    }
  }

  // -- simple test
  protected def testBatch() = {
    var sendingPackets = Queue[Packet](ConnectPacket("testendpoint1"), ConnectPacket("testendpoint2"))
    if (sendingPackets.isEmpty) {
      ""
    } else if (sendingPackets.tail.isEmpty) {
      val head = sendingPackets.head
      sendingPackets = sendingPackets.tail
      head.render.utf8String
    } else {
      var totalLength = 0
      val sb = new StringBuilder()
      var prev: Packet = null
      while (sendingPackets.nonEmpty) {
        val curr = sendingPackets.head
        curr match {
          case NoopPacket | HeartbeatPacket if curr == prev => // keep one is enough
          case _ =>
            val msg = curr.render.utf8String
            totalLength += msg.length
            sb.append('\ufffd').append(msg.length.toString).append('\ufffd').append(msg)
        }
        sendingPackets = sendingPackets.tail
        prev = curr
      }
      sb.toString
    }
  }

  def main(args: Array[String]) {
    List(
      apply("""0"""),
      apply("""0::/woot"""),
      apply("""1::/test"""),
      apply("""1::/test?arg1=1"""),
      apply("""1::/test?arg1=1&arg2=2"""),
      apply("""2"""),
      apply("""2::"""),
      apply("""3:::hello world"""),
      apply("""5:1+::{"name":"tobi"}"""),
      apply("""5:::{"name":"edwald","args":[{"a": "b"},2,"3"]}"""),
      apply("""5:21312312:test:{"name":"edwald","args":[{"a": "b"},2,"3"]}"""),
      apply("""5::/testendpoint:{"name":"Hi!","args":[]}"""),
      apply("""6:::140"""),
      apply("""\ufffd16\ufffd1::/testendpoint\ufffd17\ufffd1::/testendpoint2"""),
      apply(ByteString(-17, -65, -67, 53, 55, -17, -65, -67, 53, 58, 58, 58, 123, 34, 110, 97, 109, 101, 34, 58, 34, 99, 104, 97, 116, 34, 44, 34, 97, 114, 103, 115, 34, 58, 91, 123, 34, 116, 101, 120, 116, 34, 58, 34, 50, 56, 49, 44, 49, 51, 57, 52, 50, 57, 48, 55, 49, 53, 49, 54, 54, 34, 125, 93, 125, -17, -65, -67, 51, -17, -65, -67, 50, 58, 58)),
      apply(testBatch)) foreach println

    println(EventPacket.splitNameArgs(""" { "name" : "edwald", "args" :[{"a": "b"},2,"3"] } """))
  }
}
