package spray.contrib.socketio.packet

import java.lang.StringBuilder
import org.parboiled.errors.ErrorUtils
import org.parboiled.errors.ParsingException
import org.parboiled.scala._
import org.parboiled.Context

object PacketParser extends Parser {

  lazy val Packets = rule {
    (oneOrMore(DelimitedPacket) | Packet ~ zeroOrMore(DelimitedPacket) ~~> (_ :: _)) ~ EOI
  }

  def DelimitedPacket =
    rule { "\ufffd" ~ LongValue ~ "\ufffd" ~ Packet ~~> { (len, packet) => packet } }

  def Packet: Rule1[Packet] = rule(Disconnect
    | Connect
    | Heartbeat
    | Message
    | JsonMessage
    | Event
    | Ack
    | Error
    | Noop)

  def Disconnect =
    rule { "0" ~ { optional("::/" ~ Endpoint) ~~> (_.getOrElse("")) } ~~> DisconnectPacket }
  def Connect =
    rule { "1::/" ~ Endpoint ~ { optional("?" ~ zeroOrMore(Query, "&")) ~~> (_.getOrElse(Nil)) } ~~> ConnectPacket }
  def Heartbeat =
    rule { "2" ~ push(HeartbeatPacket) }
  def Message =
    rule { "3:" ~ GenericMessage ~~> MessagePacket }
  def JsonMessage =
    rule { "4:" ~ GenericMessage ~~> JsonMessagePacket }
  def Event =
    rule { "5:" ~ GenericMessage ~~> EventPacket }
  def Ack =
    rule { "6:::" ~ MessageId ~ { optional("+" ~ Data) ~~> (_.getOrElse("")) } ~~> AckPacket }
  def Error =
    rule { "7::" ~ Endpoint ~ ":" ~ Reason ~ { optional("+" ~ Advice) ~~> (_.getOrElse("")) } ~~> ErrorPacket }
  def Noop =
    rule { "8" ~ push(NoopPacket) }

  def GenericMessage = rule({ optional(MessageId) ~~> (_.getOrElse(-1L)) }
    ~ { optional("+" ~ push(true)) ~~> (_.getOrElse(false)) } ~ ":"
    ~ { optional(Endpoint) ~~> (_.getOrElse("")) } ~ ":"
    ~ Data)

  def MessageId = rule { Digits ~> (_.toLong) }

  def Endpoint = rule { Characters ~~> (_.toString) }

  def Query = rule { ParamLable ~ "=" ~ ParamValue ~~> ((_, _)) }
  def ParamLable = rule { Characters ~~> (_.toString) }
  def ParamValue = rule { Characters ~~> (_.toString) }

  def Reason = rule { Characters ~~> (_.toString) }
  def Advice = rule { Characters ~~> (_.toString) }

  def LongValue = rule { Digits ~> (_.toLong) }

  def Data = rule { push(new StringBuilder) ~ zeroOrMore(ANY ~:% (withContext(appendToSb(_)(_)))) ~~> (_.toString) }

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

  def NormalChar = rule { !anyOf(":?=&\"\\") ~ ANY ~:% (withContext(appendToSb(_)(_))) }

  def Unicode = rule { "u" ~ group(HexDigit ~ HexDigit ~ HexDigit ~ HexDigit) ~> (java.lang.Integer.parseInt(_, 16)) }

  def appendToSb(c: Char): Context[Any] => Unit = { ctx =>
    ctx.getValueStack.peek.asInstanceOf[StringBuilder].append(c)
    ()
  }

  /**
   * The main parsing method. Uses a ReportingParseRunner (which only reports the first error) for simplicity.
   */
  def apply(context: String): List[Packet] = apply(context.toCharArray)
  def apply(context: Array[Char]): List[Packet] = {
    val parsingResult = ReportingParseRunner(Packets).run(context)
    parsingResult.result.getOrElse {
      throw new ParsingException("Invalid Packet source:\n" + ErrorUtils.printParseErrors(parsingResult))
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
