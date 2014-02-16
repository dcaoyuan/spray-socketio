package spray.contrib.socketio.packet

import akka.util.ByteString
import org.parboiled2._
import scala.util.Failure
import scala.util.Success
import spray.json.JsonParser

class PacketParser(val input: ParserInput) extends Parser with StringBuilding {

  def Packets = rule {
    (oneOrMore(DelimitedPacket) | Packet ~ zeroOrMore(DelimitedPacket) ~> (_ +: _)) ~ EOI
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
    rule { "1::/" ~ Endpoint ~ { optional("?" ~ zeroOrMore(Query).separatedBy("&")) ~> (_.getOrElse(Nil)) } ~> ConnectPacket }
  def Heartbeat =
    rule { "2" ~ push(HeartbeatPacket) }
  def Message =
    rule { "3:" ~ GenericMessagePre ~ ":" ~ StrData ~> MessagePacket }
  def JsonMessage =
    rule { "4:" ~ GenericMessagePre ~ ":" ~ JsonData ~> JsonPacket }
  def Event =
    rule { "5:" ~ GenericMessagePre ~ ":" ~ JsonData ~> EventPacket }
  def Ack =
    rule { "6:::" ~ MessageId ~ { optional("+" ~ StrData) ~> (_.getOrElse("")) } ~> AckPacket }
  def Error =
    rule { "7::" ~ Endpoint ~ ":" ~ Reason ~ { optional("+" ~ Advice) ~> (_.getOrElse("")) } ~> ErrorPacket }
  def Noop =
    rule { "8" ~ push(NoopPacket) }

  def GenericMessagePre = rule({ optional(MessageId) ~> (_.getOrElse(-1L)) }
    ~ { optional("+" ~ push(true)) ~> (_.getOrElse(false)) } ~ ":"
    ~ { optional(optional("/") ~ Endpoint) ~> (_.getOrElse("")) })

  def JsonData = rule { StrData ~> (JsonParser(_)) }
  def StrData = rule { clearSB() ~ zeroOrMore(ANY ~ append()) ~ push(sb.toString) }

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

  def NormalChar = rule { !anyOf(":?=&\"\\") ~ ANY ~ append() }

  def Unicode = rule { 'u' ~ capture(HexDigit ~ HexDigit ~ HexDigit ~ HexDigit) ~> (java.lang.Integer.parseInt(_, 16)) }
}

object PacketParser {
  def apply(input: ByteString): Seq[Packet] = apply(input.utf8String)
  def apply(input: String): Seq[Packet] = apply(input.toCharArray)
  def apply(input: Array[Char]): Seq[Packet] = {
    val parser = new PacketParser(input)
    parser.Packets.run() match {
      case Success(packets)       => packets
      case Failure(e: ParseError) => throw e
      case Failure(e)             => throw e
    }
  }

  // -- simple test
  def main(args: Array[String]) {
    List(
      apply("""0"""),
      apply("""0::/woot"""),
      apply("""1::/test"""),
      apply("""1::/test?test=1"""),
      apply("""5:1+::{"name":"tobi"}"""),
      apply("""5:::{"name":"edwald","args":[{"a": "b"},2,"3"]}"""),
      apply("""5:21312312:test:{"name":"edwald","args":[{"a": "b"},2,"3"]}"""),
      apply("""6:::140""")) foreach println
  }

}
