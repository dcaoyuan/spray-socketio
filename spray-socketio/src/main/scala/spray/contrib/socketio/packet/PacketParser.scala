package spray.contrib.socketio.packet

import akka.util.ByteString
import org.parboiled2._
import scala.util.Try

final class PacketParser(val input: ParserInput) extends Parser with StringBuilding {

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
  def apply(input: ByteString): Try[Seq[Packet]] = apply(input.utf8String)
  def apply(input: String): Try[Seq[Packet]] = apply(input.toCharArray)
  def apply(input: Array[Char]): Try[Seq[Packet]] = new PacketParser(input).Packets.run()
}
