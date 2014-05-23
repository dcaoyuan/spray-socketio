package spray.contrib.socketio.packet

import akka.util.ByteString
import org.parboiled.Context
import org.parboiled.errors.ErrorUtils
import org.parboiled.errors.ParsingException
import org.parboiled.scala._
import java.lang.StringBuilder

import scala.util.Failure
import scala.util.Success
import scala.util.Try

/**
 *  To get me back to parboiled2, @see
 *  https://github.com/wandoulabs/spray-socketio/blob/3354b4a55b12a38bdcb01f21ba671465a6a8d646/spray-socketio/src/main/scala/spray/contrib/socketio/packet/PacketParser.scala
 */
object PacketParser extends Parser {

  def Packets = rule {
    (oneOrMore(DelimitedPacket) | { Packet ~~> (List(_)) /* ~ zeroOrMore(DelimitedPacket) ~> (_ +: _) */ }) ~ EOI
  }

  def DelimitedPacket =
    rule { "\ufffd" ~ LongValue ~ "\ufffd" ~ Packet ~~> { (len, packet) => packet } }

  def Packet = rule {
    Disconnect |
      Connect |
      Heartbeat |
      Message |
      JsonMessage |
      Event |
      Ack |
      Error |
      Noop
  }

  def Disconnect =
    rule { "0" ~ { optional("::" ~ optional("/" ~ Endpoint) ~~> (_.getOrElse(""))) ~~> (_.getOrElse("")) } ~~> DisconnectPacket }
  def Connect =
    rule { "1::" ~ { optional("/" ~ Endpoint) ~~> (_.getOrElse("")) } ~ { optional("?" ~ zeroOrMore(Query, "&")) ~~> (_.getOrElse(Nil)) } ~~> ConnectPacket }
  def Heartbeat =
    rule { "2" ~ zeroOrMore(":") ~ push(HeartbeatPacket) }
  def Message =
    rule { "3:" ~ GenericMessagePre ~ ":" ~ StrData ~~> MessagePacket }
  def JsonMessage =
    rule { "4:" ~ GenericMessagePre ~ ":" ~ StrData ~~> JsonPacket }
  def Event =
    rule { "5:" ~ GenericMessagePre ~ ":" ~ StrData ~~> (EventPacket(_, _, _, _)) }
  def Ack =
    rule { "6:::" ~ MessageId ~ { optional("+" ~ StrData) ~~> (_.getOrElse("")) } ~~> AckPacket }
  def Error =
    rule { "7::" ~ Endpoint ~ ":" ~ Reason ~ { optional("+" ~ Advice) ~~> (_.getOrElse("")) } ~~> ErrorPacket }
  def Noop =
    rule { "8" ~ push(NoopPacket) }

  def GenericMessagePre = rule({ optional(MessageId) ~~> (_.getOrElse(-1L)) }
    ~ { optional("+" ~ push(true)) ~~> (_.getOrElse(false)) } ~ ":"
    ~ { optional(optional("/") ~ Endpoint) ~~> (_.getOrElse("")) })

  def StrData = rule { push(new StringBuilder) ~ zeroOrMore(!anyOf("\ufffd") ~ ANY ~:% (withContext(appendToSb(_)(_)))) ~~> (_.toString) }

  def MessageId = rule { Digits ~> (_.toLong) }

  def Endpoint = rule { Characters ~~> (_.toString) }

  def Query = rule { ParamLable ~ "=" ~ ParamValue ~~> ((_, _)) }
  def ParamLable = rule { Characters ~~> (_.toString) }
  def ParamValue = rule { Characters ~~> (_.toString) }

  def Reason = rule { Characters ~~> (_.toString) }
  def Advice = rule { Characters ~~> (_.toString) }

  def LongValue = rule { Digits ~> (_.toLong) }

  def Digit = rule { "0" - "9" }

  def Digits = rule { oneOrMore(Digit) }

  def HexDigit = rule { "0" - "9" | "a" - "f" | "A" - "F" }

  def Characters = rule { push(new StringBuilder) ~ zeroOrMore("\\" ~ EscapedChar | NormalChar) }

  def EscapedChar = rule(
    anyOf("\"\\/") ~:% withContext(appendToSb(_)(_))
      | "b" ~ appendToSb('\b')
      | "f" ~ appendToSb('\f')
      | "n" ~ appendToSb('\n')
      | "r" ~ appendToSb('\r')
      | "t" ~ appendToSb('\t')
      | Unicode ~~% withContext((code, ctx) => appendToSb(code.asInstanceOf[Char])(ctx)))

  def NormalChar = rule { !anyOf("\ufffd:?=&\"\\") ~ ANY ~:% (withContext(appendToSb(_)(_))) }

  def Unicode = rule { "u" ~ group(HexDigit ~ HexDigit ~ HexDigit ~ HexDigit) ~> (java.lang.Integer.parseInt(_, 16)) }

  // helper method for fast string building
  // for maximum performance we use a somewhat unorthodox parsing technique that is a bit more verbose (and somewhat
  // less readable) but reduces object allocations during the parsing run to a minimum:
  // the Characters rules pushes a StringBuilder object onto the stack which is then directly fed with matched
  // and unescaped characters in the sub rules (i.e. no string allocations and value stack operation required)
  def appendToSb(c: Char): Context[Any] => Unit = { ctx =>
    ctx.getValueStack.peek.asInstanceOf[StringBuilder].append(c)
    ()
  }

  def apply(input: ByteString): Try[Seq[Packet]] = apply(input.utf8String)
  def apply(input: String): Try[Seq[Packet]] = apply(input.toCharArray)
  def apply(input: Array[Char]): Try[Seq[Packet]] = {
    val parsingResult = ReportingParseRunner(Packets).run(input)
    parsingResult.result match {
      case Some(x) => Success(x)
      case None    => Failure(new ParsingException("Invalid Packet source:\n" + ErrorUtils.printParseErrors(parsingResult)))
    }
  }
}

