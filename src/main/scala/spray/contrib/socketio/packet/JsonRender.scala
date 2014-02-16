/*
 * Copyright (C) 2009-2011 Mathias Doenitz
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package spray.contrib.socketio.packet

import akka.util.ByteString
import akka.util.ByteStringBuilder
import annotation.tailrec
import spray.json.JsArray
import spray.json.JsFalse
import spray.json.JsNull
import spray.json.JsNumber
import spray.json.JsObject
import spray.json.JsString
import spray.json.JsTrue
import spray.json.JsValue

/**
 * A JsonPrinter serializes a JSON AST to a String.
 */
object JsonRender {

  def apply(x: JsValue, builder: ByteStringBuilder): ByteStringBuilder =
    apply(x, None, builder)

  def apply(x: JsValue, jsonpCallback: String, builder: ByteStringBuilder): ByteStringBuilder =
    apply(x, Some(jsonpCallback), builder)

  def apply(x: JsValue, jsonpCallback: Option[String], sb: ByteStringBuilder): ByteStringBuilder = {
    jsonpCallback match {
      case Some(callback) => {
        sb.putBytes(callback.getBytes).putByte('(')
        print(x, sb)
        sb.putByte(')')
      }
      case None => print(x, sb)
    }
    sb
  }

  val EscapedQuote = "\\\"".getBytes
  val EscapedBackslash = "\\\\".getBytes
  val EscapedBackspace = "\\b".getBytes
  val EscapedFormFeed = "\\f".getBytes
  val EscapedLineFeed = "\\n".getBytes
  val EscapedCarriageReturn = "\\r".getBytes
  val EscapedTab = "\\t".getBytes
  val EscapedUtf3 = "\\u000".getBytes
  val EscapedUtf2 = "\\u00".getBytes
  val EscapedUtf1 = "\\u0".getBytes
  val EscapedUtf0 = "\\u".getBytes
  val NullBytes = "null".getBytes
  val TrueBytes = "true".getBytes
  val FalseBytes = "false".getBytes

  private[this] val mask = new Array[Int](4)
  private[this] def ascii(c: Char): Int = c & ((c - 127) >> 31) // branchless for `if (c <= 127) c else 0`
  private[this] def mark(c: Char): Unit = {
    val b = ascii(c)
    mask(b >> 5) |= 1 << (b & 0x1F)
  }
  private[this] def mark(range: scala.collection.immutable.NumericRange[Char]): Unit = range foreach (mark)

  mark('\u0000' to '\u0019')
  mark('\u007f')
  mark('"')
  mark('\\')

  def requiresEncoding(c: Char): Boolean = {
    val b = ascii(c)
    (mask(b >> 5) & (1 << (b & 0x1F))) != 0
  }

  protected def printLeaf(x: JsValue, sb: ByteStringBuilder) {
    x match {
      case JsNull      => sb.putBytes(NullBytes)
      case JsTrue      => sb.putBytes(TrueBytes)
      case JsFalse     => sb.putBytes(FalseBytes)
      case JsNumber(x) => sb.putBytes(x.toString.getBytes)
      case JsString(x) => printString(x, sb)
      case _           => throw new IllegalStateException
    }
  }

  protected def printString(s: String, sb: ByteStringBuilder) {
    @tailrec def firstToBeEncoded(ix: Int = 0): Int =
      if (ix == s.length) -1 else if (requiresEncoding(s.charAt(ix))) ix else firstToBeEncoded(ix + 1)

    val sbytes = s.getBytes
    sb.putByte('"')
    firstToBeEncoded() match {
      case -1 ⇒ sb.putBytes(sbytes)
      case first ⇒
        sb.putBytes(sbytes, 0, first)
        @tailrec def append(ix: Int): Unit =
          if (ix < s.length) {
            s.charAt(ix) match {
              case c if !requiresEncoding(c) => sb.putByte(c.toByte)
              case '"'                       => sb.putBytes(EscapedQuote)
              case '\\'                      => sb.putBytes(EscapedBackslash)
              case '\b'                      => sb.putBytes(EscapedBackspace)
              case '\f'                      => sb.putBytes(EscapedFormFeed)
              case '\n'                      => sb.putBytes(EscapedLineFeed)
              case '\r'                      => sb.putBytes(EscapedCarriageReturn)
              case '\t'                      => sb.putBytes(EscapedTab)
              case x if x <= 0xF             => sb.putBytes(EscapedUtf3).putBytes(Integer.toHexString(x).getBytes)
              case x if x <= 0xFF            => sb.putBytes(EscapedUtf2).putBytes(Integer.toHexString(x).getBytes)
              case x if x <= 0xFFF           => sb.putBytes(EscapedUtf1).putBytes(Integer.toHexString(x).getBytes)
              case x                         => sb.putBytes(EscapedUtf0).putBytes(Integer.toHexString(x).getBytes)
            }
            append(ix + 1)
          }
        append(first)
    }
    sb.putByte('"')
  }

  protected def printSeq[A](iterable: Iterable[A], printSeparator: => Unit)(f: A => Unit) {
    var first = true
    iterable.foreach { a =>
      if (first) first = false else printSeparator
      f(a)
    }
  }

  def print(x: JsValue, sb: ByteStringBuilder) {
    x match {
      case JsObject(x) => printObject(x, sb)
      case JsArray(x)  => printArray(x, sb)
      case _           => printLeaf(x, sb)
    }
  }

  private def printObject(members: Map[String, JsValue], sb: ByteStringBuilder) {
    sb.putByte('{')
    printSeq(members, sb.putByte(',')) { m =>
      printString(m._1, sb)
      sb.putByte(':')
      print(m._2, sb)
    }
    sb.putByte('}')
  }

  private def printArray(elements: List[JsValue], sb: ByteStringBuilder) {
    sb.putByte('[')
    printSeq(elements, sb.putByte(','))(print(_, sb))
    sb.putByte(']')
  }

}

