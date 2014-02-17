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
 * A JsonRender serializes a JSON AST to an ByteStringBuilder.
 * Derivated from spray.json.JsonPrinter and CompactJsonPrinter
 */
object JsonRender {

  def apply(x: JsValue, builder: ByteStringBuilder): ByteStringBuilder =
    apply(x, None, builder)

  def apply(x: JsValue, jsonpCallback: String, builder: ByteStringBuilder): ByteStringBuilder =
    apply(x, Some(jsonpCallback), builder)

  def apply(x: JsValue, jsonpCallback: Option[String], builder: ByteStringBuilder): ByteStringBuilder = {
    jsonpCallback match {
      case Some(callback) => {
        builder.putBytes(callback.getBytes).putByte('(')
        put(x, builder)
        builder.putByte(')')
      }
      case None => put(x, builder)
    }
    builder
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

  protected def putLeaf(x: JsValue, sb: ByteStringBuilder) {
    x match {
      case JsNull      => sb.putBytes(NullBytes)
      case JsTrue      => sb.putBytes(TrueBytes)
      case JsFalse     => sb.putBytes(FalseBytes)
      case JsNumber(x) => sb.putBytes(x.toString.getBytes)
      case JsString(x) => putString(x, sb)
      case _           => throw new IllegalStateException
    }
  }

  protected def putString(s: String, sb: ByteStringBuilder) {
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

  protected def putSeq[A](iterable: Iterable[A], printSeparator: => Unit)(f: A => Unit) {
    var first = true
    iterable.foreach { a =>
      if (first) first = false else printSeparator
      f(a)
    }
  }

  def put(x: JsValue, sb: ByteStringBuilder) {
    x match {
      case JsObject(x) => putObject(x, sb)
      case JsArray(x)  => putArray(x, sb)
      case _           => putLeaf(x, sb)
    }
  }

  private def putObject(members: Map[String, JsValue], sb: ByteStringBuilder) {
    sb.putByte('{')
    putSeq(members, sb.putByte(',')) { m =>
      putString(m._1, sb)
      sb.putByte(':')
      put(m._2, sb)
    }
    sb.putByte('}')
  }

  private def putArray(elements: List[JsValue], sb: ByteStringBuilder) {
    sb.putByte('[')
    putSeq(elements, sb.putByte(','))(put(_, sb))
    sb.putByte(']')
  }

}

