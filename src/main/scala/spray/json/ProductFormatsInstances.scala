/*
 * Copyright (C) 2011,2012 Mathias Doenitz, Johannes Rudolph
 *
 * Licensed under the Apache License, Version 2.21 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.21
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package spray.json

trait ProductFormatsInstances { self: ProductFormats with StandardFormats =>
  // Case classes with 1 parameters

  def jsonFormat1[P1 :JF, T <: Product :ClassManifest](construct: (P1) => T): RootJsonFormat[T] = {
    val Array(p1) = extractFieldNames(classManifest[T])
    jsonFormat(construct, p1)
  }
  def jsonFormat[P1 :JF, T <: Product](construct: (P1) => T, fieldName1: String): RootJsonFormat[T] = new RootJsonFormat[T]{
    def write(p: T) = {
      val fields = new collection.mutable.ListBuffer[(String, JsValue)]
      fields.sizeHint(1 * 2)
      fields ++= productElement2Field[P1](fieldName1, p, 0)
      JsObject(fields: _*)
    }
    def read(value: JsValue) = {
      val p1V = fromField[P1](value, fieldName1)
      construct(p1V)
    }
  }


  // Case classes with 2 parameters

  def jsonFormat2[P1 :JF, P2 :JF, T <: Product :ClassManifest](construct: (P1, P2) => T): RootJsonFormat[T] = {
    val Array(p1, p2) = extractFieldNames(classManifest[T])
    jsonFormat(construct, p1, p2)
  }
  def jsonFormat[P1 :JF, P2 :JF, T <: Product](construct: (P1, P2) => T, fieldName1: String, fieldName2: String): RootJsonFormat[T] = new RootJsonFormat[T]{
    def write(p: T) = {
      val fields = new collection.mutable.ListBuffer[(String, JsValue)]
      fields.sizeHint(2 * 2)
      fields ++= productElement2Field[P1](fieldName1, p, 0)
      fields ++= productElement2Field[P2](fieldName2, p, 1)
      JsObject(fields: _*)
    }
    def read(value: JsValue) = {
      val p1V = fromField[P1](value, fieldName1)
      val p2V = fromField[P2](value, fieldName2)
      construct(p1V, p2V)
    }
  }


  // Case classes with 3 parameters

  def jsonFormat3[P1 :JF, P2 :JF, P3 :JF, T <: Product :ClassManifest](construct: (P1, P2, P3) => T): RootJsonFormat[T] = {
    val Array(p1, p2, p3) = extractFieldNames(classManifest[T])
    jsonFormat(construct, p1, p2, p3)
  }
  def jsonFormat[P1 :JF, P2 :JF, P3 :JF, T <: Product](construct: (P1, P2, P3) => T, fieldName1: String, fieldName2: String, fieldName3: String): RootJsonFormat[T] = new RootJsonFormat[T]{
    def write(p: T) = {
      val fields = new collection.mutable.ListBuffer[(String, JsValue)]
      fields.sizeHint(3 * 2)
      fields ++= productElement2Field[P1](fieldName1, p, 0)
      fields ++= productElement2Field[P2](fieldName2, p, 1)
      fields ++= productElement2Field[P3](fieldName3, p, 2)
      JsObject(fields: _*)
    }
    def read(value: JsValue) = {
      val p1V = fromField[P1](value, fieldName1)
      val p2V = fromField[P2](value, fieldName2)
      val p3V = fromField[P3](value, fieldName3)
      construct(p1V, p2V, p3V)
    }
  }


  // Case classes with 4 parameters

  def jsonFormat4[P1 :JF, P2 :JF, P3 :JF, P4 :JF, T <: Product :ClassManifest](construct: (P1, P2, P3, P4) => T): RootJsonFormat[T] = {
    val Array(p1, p2, p3, p4) = extractFieldNames(classManifest[T])
    jsonFormat(construct, p1, p2, p3, p4)
  }
  def jsonFormat[P1 :JF, P2 :JF, P3 :JF, P4 :JF, T <: Product](construct: (P1, P2, P3, P4) => T, fieldName1: String, fieldName2: String, fieldName3: String, fieldName4: String): RootJsonFormat[T] = new RootJsonFormat[T]{
    def write(p: T) = {
      val fields = new collection.mutable.ListBuffer[(String, JsValue)]
      fields.sizeHint(4 * 2)
      fields ++= productElement2Field[P1](fieldName1, p, 0)
      fields ++= productElement2Field[P2](fieldName2, p, 1)
      fields ++= productElement2Field[P3](fieldName3, p, 2)
      fields ++= productElement2Field[P4](fieldName4, p, 3)
      JsObject(fields: _*)
    }
    def read(value: JsValue) = {
      val p1V = fromField[P1](value, fieldName1)
      val p2V = fromField[P2](value, fieldName2)
      val p3V = fromField[P3](value, fieldName3)
      val p4V = fromField[P4](value, fieldName4)
      construct(p1V, p2V, p3V, p4V)
    }
  }


  // Case classes with 5 parameters

  def jsonFormat5[P1 :JF, P2 :JF, P3 :JF, P4 :JF, P5 :JF, T <: Product :ClassManifest](construct: (P1, P2, P3, P4, P5) => T): RootJsonFormat[T] = {
    val Array(p1, p2, p3, p4, p5) = extractFieldNames(classManifest[T])
    jsonFormat(construct, p1, p2, p3, p4, p5)
  }
  def jsonFormat[P1 :JF, P2 :JF, P3 :JF, P4 :JF, P5 :JF, T <: Product](construct: (P1, P2, P3, P4, P5) => T, fieldName1: String, fieldName2: String, fieldName3: String, fieldName4: String, fieldName5: String): RootJsonFormat[T] = new RootJsonFormat[T]{
    def write(p: T) = {
      val fields = new collection.mutable.ListBuffer[(String, JsValue)]
      fields.sizeHint(5 * 2)
      fields ++= productElement2Field[P1](fieldName1, p, 0)
      fields ++= productElement2Field[P2](fieldName2, p, 1)
      fields ++= productElement2Field[P3](fieldName3, p, 2)
      fields ++= productElement2Field[P4](fieldName4, p, 3)
      fields ++= productElement2Field[P5](fieldName5, p, 4)
      JsObject(fields: _*)
    }
    def read(value: JsValue) = {
      val p1V = fromField[P1](value, fieldName1)
      val p2V = fromField[P2](value, fieldName2)
      val p3V = fromField[P3](value, fieldName3)
      val p4V = fromField[P4](value, fieldName4)
      val p5V = fromField[P5](value, fieldName5)
      construct(p1V, p2V, p3V, p4V, p5V)
    }
  }


  // Case classes with 6 parameters

  def jsonFormat6[P1 :JF, P2 :JF, P3 :JF, P4 :JF, P5 :JF, P6 :JF, T <: Product :ClassManifest](construct: (P1, P2, P3, P4, P5, P6) => T): RootJsonFormat[T] = {
    val Array(p1, p2, p3, p4, p5, p6) = extractFieldNames(classManifest[T])
    jsonFormat(construct, p1, p2, p3, p4, p5, p6)
  }
  def jsonFormat[P1 :JF, P2 :JF, P3 :JF, P4 :JF, P5 :JF, P6 :JF, T <: Product](construct: (P1, P2, P3, P4, P5, P6) => T, fieldName1: String, fieldName2: String, fieldName3: String, fieldName4: String, fieldName5: String, fieldName6: String): RootJsonFormat[T] = new RootJsonFormat[T]{
    def write(p: T) = {
      val fields = new collection.mutable.ListBuffer[(String, JsValue)]
      fields.sizeHint(6 * 2)
      fields ++= productElement2Field[P1](fieldName1, p, 0)
      fields ++= productElement2Field[P2](fieldName2, p, 1)
      fields ++= productElement2Field[P3](fieldName3, p, 2)
      fields ++= productElement2Field[P4](fieldName4, p, 3)
      fields ++= productElement2Field[P5](fieldName5, p, 4)
      fields ++= productElement2Field[P6](fieldName6, p, 5)
      JsObject(fields: _*)
    }
    def read(value: JsValue) = {
      val p1V = fromField[P1](value, fieldName1)
      val p2V = fromField[P2](value, fieldName2)
      val p3V = fromField[P3](value, fieldName3)
      val p4V = fromField[P4](value, fieldName4)
      val p5V = fromField[P5](value, fieldName5)
      val p6V = fromField[P6](value, fieldName6)
      construct(p1V, p2V, p3V, p4V, p5V, p6V)
    }
  }


  // Case classes with 7 parameters

  def jsonFormat7[P1 :JF, P2 :JF, P3 :JF, P4 :JF, P5 :JF, P6 :JF, P7 :JF, T <: Product :ClassManifest](construct: (P1, P2, P3, P4, P5, P6, P7) => T): RootJsonFormat[T] = {
    val Array(p1, p2, p3, p4, p5, p6, p7) = extractFieldNames(classManifest[T])
    jsonFormat(construct, p1, p2, p3, p4, p5, p6, p7)
  }
  def jsonFormat[P1 :JF, P2 :JF, P3 :JF, P4 :JF, P5 :JF, P6 :JF, P7 :JF, T <: Product](construct: (P1, P2, P3, P4, P5, P6, P7) => T, fieldName1: String, fieldName2: String, fieldName3: String, fieldName4: String, fieldName5: String, fieldName6: String, fieldName7: String): RootJsonFormat[T] = new RootJsonFormat[T]{
    def write(p: T) = {
      val fields = new collection.mutable.ListBuffer[(String, JsValue)]
      fields.sizeHint(7 * 2)
      fields ++= productElement2Field[P1](fieldName1, p, 0)
      fields ++= productElement2Field[P2](fieldName2, p, 1)
      fields ++= productElement2Field[P3](fieldName3, p, 2)
      fields ++= productElement2Field[P4](fieldName4, p, 3)
      fields ++= productElement2Field[P5](fieldName5, p, 4)
      fields ++= productElement2Field[P6](fieldName6, p, 5)
      fields ++= productElement2Field[P7](fieldName7, p, 6)
      JsObject(fields: _*)
    }
    def read(value: JsValue) = {
      val p1V = fromField[P1](value, fieldName1)
      val p2V = fromField[P2](value, fieldName2)
      val p3V = fromField[P3](value, fieldName3)
      val p4V = fromField[P4](value, fieldName4)
      val p5V = fromField[P5](value, fieldName5)
      val p6V = fromField[P6](value, fieldName6)
      val p7V = fromField[P7](value, fieldName7)
      construct(p1V, p2V, p3V, p4V, p5V, p6V, p7V)
    }
  }


  // Case classes with 8 parameters

  def jsonFormat8[P1 :JF, P2 :JF, P3 :JF, P4 :JF, P5 :JF, P6 :JF, P7 :JF, P8 :JF, T <: Product :ClassManifest](construct: (P1, P2, P3, P4, P5, P6, P7, P8) => T): RootJsonFormat[T] = {
    val Array(p1, p2, p3, p4, p5, p6, p7, p8) = extractFieldNames(classManifest[T])
    jsonFormat(construct, p1, p2, p3, p4, p5, p6, p7, p8)
  }
  def jsonFormat[P1 :JF, P2 :JF, P3 :JF, P4 :JF, P5 :JF, P6 :JF, P7 :JF, P8 :JF, T <: Product](construct: (P1, P2, P3, P4, P5, P6, P7, P8) => T, fieldName1: String, fieldName2: String, fieldName3: String, fieldName4: String, fieldName5: String, fieldName6: String, fieldName7: String, fieldName8: String): RootJsonFormat[T] = new RootJsonFormat[T]{
    def write(p: T) = {
      val fields = new collection.mutable.ListBuffer[(String, JsValue)]
      fields.sizeHint(8 * 2)
      fields ++= productElement2Field[P1](fieldName1, p, 0)
      fields ++= productElement2Field[P2](fieldName2, p, 1)
      fields ++= productElement2Field[P3](fieldName3, p, 2)
      fields ++= productElement2Field[P4](fieldName4, p, 3)
      fields ++= productElement2Field[P5](fieldName5, p, 4)
      fields ++= productElement2Field[P6](fieldName6, p, 5)
      fields ++= productElement2Field[P7](fieldName7, p, 6)
      fields ++= productElement2Field[P8](fieldName8, p, 7)
      JsObject(fields: _*)
    }
    def read(value: JsValue) = {
      val p1V = fromField[P1](value, fieldName1)
      val p2V = fromField[P2](value, fieldName2)
      val p3V = fromField[P3](value, fieldName3)
      val p4V = fromField[P4](value, fieldName4)
      val p5V = fromField[P5](value, fieldName5)
      val p6V = fromField[P6](value, fieldName6)
      val p7V = fromField[P7](value, fieldName7)
      val p8V = fromField[P8](value, fieldName8)
      construct(p1V, p2V, p3V, p4V, p5V, p6V, p7V, p8V)
    }
  }


  // Case classes with 9 parameters

  def jsonFormat9[P1 :JF, P2 :JF, P3 :JF, P4 :JF, P5 :JF, P6 :JF, P7 :JF, P8 :JF, P9 :JF, T <: Product :ClassManifest](construct: (P1, P2, P3, P4, P5, P6, P7, P8, P9) => T): RootJsonFormat[T] = {
    val Array(p1, p2, p3, p4, p5, p6, p7, p8, p9) = extractFieldNames(classManifest[T])
    jsonFormat(construct, p1, p2, p3, p4, p5, p6, p7, p8, p9)
  }
  def jsonFormat[P1 :JF, P2 :JF, P3 :JF, P4 :JF, P5 :JF, P6 :JF, P7 :JF, P8 :JF, P9 :JF, T <: Product](construct: (P1, P2, P3, P4, P5, P6, P7, P8, P9) => T, fieldName1: String, fieldName2: String, fieldName3: String, fieldName4: String, fieldName5: String, fieldName6: String, fieldName7: String, fieldName8: String, fieldName9: String): RootJsonFormat[T] = new RootJsonFormat[T]{
    def write(p: T) = {
      val fields = new collection.mutable.ListBuffer[(String, JsValue)]
      fields.sizeHint(9 * 2)
      fields ++= productElement2Field[P1](fieldName1, p, 0)
      fields ++= productElement2Field[P2](fieldName2, p, 1)
      fields ++= productElement2Field[P3](fieldName3, p, 2)
      fields ++= productElement2Field[P4](fieldName4, p, 3)
      fields ++= productElement2Field[P5](fieldName5, p, 4)
      fields ++= productElement2Field[P6](fieldName6, p, 5)
      fields ++= productElement2Field[P7](fieldName7, p, 6)
      fields ++= productElement2Field[P8](fieldName8, p, 7)
      fields ++= productElement2Field[P9](fieldName9, p, 8)
      JsObject(fields: _*)
    }
    def read(value: JsValue) = {
      val p1V = fromField[P1](value, fieldName1)
      val p2V = fromField[P2](value, fieldName2)
      val p3V = fromField[P3](value, fieldName3)
      val p4V = fromField[P4](value, fieldName4)
      val p5V = fromField[P5](value, fieldName5)
      val p6V = fromField[P6](value, fieldName6)
      val p7V = fromField[P7](value, fieldName7)
      val p8V = fromField[P8](value, fieldName8)
      val p9V = fromField[P9](value, fieldName9)
      construct(p1V, p2V, p3V, p4V, p5V, p6V, p7V, p8V, p9V)
    }
  }


  // Case classes with 10 parameters

  def jsonFormat10[P1 :JF, P2 :JF, P3 :JF, P4 :JF, P5 :JF, P6 :JF, P7 :JF, P8 :JF, P9 :JF, P10 :JF, T <: Product :ClassManifest](construct: (P1, P2, P3, P4, P5, P6, P7, P8, P9, P10) => T): RootJsonFormat[T] = {
    val Array(p1, p2, p3, p4, p5, p6, p7, p8, p9, p10) = extractFieldNames(classManifest[T])
    jsonFormat(construct, p1, p2, p3, p4, p5, p6, p7, p8, p9, p10)
  }
  def jsonFormat[P1 :JF, P2 :JF, P3 :JF, P4 :JF, P5 :JF, P6 :JF, P7 :JF, P8 :JF, P9 :JF, P10 :JF, T <: Product](construct: (P1, P2, P3, P4, P5, P6, P7, P8, P9, P10) => T, fieldName1: String, fieldName2: String, fieldName3: String, fieldName4: String, fieldName5: String, fieldName6: String, fieldName7: String, fieldName8: String, fieldName9: String, fieldName10: String): RootJsonFormat[T] = new RootJsonFormat[T]{
    def write(p: T) = {
      val fields = new collection.mutable.ListBuffer[(String, JsValue)]
      fields.sizeHint(10 * 2)
      fields ++= productElement2Field[P1](fieldName1, p, 0)
      fields ++= productElement2Field[P2](fieldName2, p, 1)
      fields ++= productElement2Field[P3](fieldName3, p, 2)
      fields ++= productElement2Field[P4](fieldName4, p, 3)
      fields ++= productElement2Field[P5](fieldName5, p, 4)
      fields ++= productElement2Field[P6](fieldName6, p, 5)
      fields ++= productElement2Field[P7](fieldName7, p, 6)
      fields ++= productElement2Field[P8](fieldName8, p, 7)
      fields ++= productElement2Field[P9](fieldName9, p, 8)
      fields ++= productElement2Field[P10](fieldName10, p, 9)
      JsObject(fields: _*)
    }
    def read(value: JsValue) = {
      val p1V = fromField[P1](value, fieldName1)
      val p2V = fromField[P2](value, fieldName2)
      val p3V = fromField[P3](value, fieldName3)
      val p4V = fromField[P4](value, fieldName4)
      val p5V = fromField[P5](value, fieldName5)
      val p6V = fromField[P6](value, fieldName6)
      val p7V = fromField[P7](value, fieldName7)
      val p8V = fromField[P8](value, fieldName8)
      val p9V = fromField[P9](value, fieldName9)
      val p10V = fromField[P10](value, fieldName10)
      construct(p1V, p2V, p3V, p4V, p5V, p6V, p7V, p8V, p9V, p10V)
    }
  }


  // Case classes with 11 parameters

  def jsonFormat11[P1 :JF, P2 :JF, P3 :JF, P4 :JF, P5 :JF, P6 :JF, P7 :JF, P8 :JF, P9 :JF, P10 :JF, P11 :JF, T <: Product :ClassManifest](construct: (P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11) => T): RootJsonFormat[T] = {
    val Array(p1, p2, p3, p4, p5, p6, p7, p8, p9, p10, p11) = extractFieldNames(classManifest[T])
    jsonFormat(construct, p1, p2, p3, p4, p5, p6, p7, p8, p9, p10, p11)
  }
  def jsonFormat[P1 :JF, P2 :JF, P3 :JF, P4 :JF, P5 :JF, P6 :JF, P7 :JF, P8 :JF, P9 :JF, P10 :JF, P11 :JF, T <: Product](construct: (P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11) => T, fieldName1: String, fieldName2: String, fieldName3: String, fieldName4: String, fieldName5: String, fieldName6: String, fieldName7: String, fieldName8: String, fieldName9: String, fieldName10: String, fieldName11: String): RootJsonFormat[T] = new RootJsonFormat[T]{
    def write(p: T) = {
      val fields = new collection.mutable.ListBuffer[(String, JsValue)]
      fields.sizeHint(11 * 2)
      fields ++= productElement2Field[P1](fieldName1, p, 0)
      fields ++= productElement2Field[P2](fieldName2, p, 1)
      fields ++= productElement2Field[P3](fieldName3, p, 2)
      fields ++= productElement2Field[P4](fieldName4, p, 3)
      fields ++= productElement2Field[P5](fieldName5, p, 4)
      fields ++= productElement2Field[P6](fieldName6, p, 5)
      fields ++= productElement2Field[P7](fieldName7, p, 6)
      fields ++= productElement2Field[P8](fieldName8, p, 7)
      fields ++= productElement2Field[P9](fieldName9, p, 8)
      fields ++= productElement2Field[P10](fieldName10, p, 9)
      fields ++= productElement2Field[P11](fieldName11, p, 10)
      JsObject(fields: _*)
    }
    def read(value: JsValue) = {
      val p1V = fromField[P1](value, fieldName1)
      val p2V = fromField[P2](value, fieldName2)
      val p3V = fromField[P3](value, fieldName3)
      val p4V = fromField[P4](value, fieldName4)
      val p5V = fromField[P5](value, fieldName5)
      val p6V = fromField[P6](value, fieldName6)
      val p7V = fromField[P7](value, fieldName7)
      val p8V = fromField[P8](value, fieldName8)
      val p9V = fromField[P9](value, fieldName9)
      val p10V = fromField[P10](value, fieldName10)
      val p11V = fromField[P11](value, fieldName11)
      construct(p1V, p2V, p3V, p4V, p5V, p6V, p7V, p8V, p9V, p10V, p11V)
    }
  }


  // Case classes with 12 parameters

  def jsonFormat12[P1 :JF, P2 :JF, P3 :JF, P4 :JF, P5 :JF, P6 :JF, P7 :JF, P8 :JF, P9 :JF, P10 :JF, P11 :JF, P12 :JF, T <: Product :ClassManifest](construct: (P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12) => T): RootJsonFormat[T] = {
    val Array(p1, p2, p3, p4, p5, p6, p7, p8, p9, p10, p11, p12) = extractFieldNames(classManifest[T])
    jsonFormat(construct, p1, p2, p3, p4, p5, p6, p7, p8, p9, p10, p11, p12)
  }
  def jsonFormat[P1 :JF, P2 :JF, P3 :JF, P4 :JF, P5 :JF, P6 :JF, P7 :JF, P8 :JF, P9 :JF, P10 :JF, P11 :JF, P12 :JF, T <: Product](construct: (P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12) => T, fieldName1: String, fieldName2: String, fieldName3: String, fieldName4: String, fieldName5: String, fieldName6: String, fieldName7: String, fieldName8: String, fieldName9: String, fieldName10: String, fieldName11: String, fieldName12: String): RootJsonFormat[T] = new RootJsonFormat[T]{
    def write(p: T) = {
      val fields = new collection.mutable.ListBuffer[(String, JsValue)]
      fields.sizeHint(12 * 2)
      fields ++= productElement2Field[P1](fieldName1, p, 0)
      fields ++= productElement2Field[P2](fieldName2, p, 1)
      fields ++= productElement2Field[P3](fieldName3, p, 2)
      fields ++= productElement2Field[P4](fieldName4, p, 3)
      fields ++= productElement2Field[P5](fieldName5, p, 4)
      fields ++= productElement2Field[P6](fieldName6, p, 5)
      fields ++= productElement2Field[P7](fieldName7, p, 6)
      fields ++= productElement2Field[P8](fieldName8, p, 7)
      fields ++= productElement2Field[P9](fieldName9, p, 8)
      fields ++= productElement2Field[P10](fieldName10, p, 9)
      fields ++= productElement2Field[P11](fieldName11, p, 10)
      fields ++= productElement2Field[P12](fieldName12, p, 11)
      JsObject(fields: _*)
    }
    def read(value: JsValue) = {
      val p1V = fromField[P1](value, fieldName1)
      val p2V = fromField[P2](value, fieldName2)
      val p3V = fromField[P3](value, fieldName3)
      val p4V = fromField[P4](value, fieldName4)
      val p5V = fromField[P5](value, fieldName5)
      val p6V = fromField[P6](value, fieldName6)
      val p7V = fromField[P7](value, fieldName7)
      val p8V = fromField[P8](value, fieldName8)
      val p9V = fromField[P9](value, fieldName9)
      val p10V = fromField[P10](value, fieldName10)
      val p11V = fromField[P11](value, fieldName11)
      val p12V = fromField[P12](value, fieldName12)
      construct(p1V, p2V, p3V, p4V, p5V, p6V, p7V, p8V, p9V, p10V, p11V, p12V)
    }
  }


  // Case classes with 13 parameters

  def jsonFormat13[P1 :JF, P2 :JF, P3 :JF, P4 :JF, P5 :JF, P6 :JF, P7 :JF, P8 :JF, P9 :JF, P10 :JF, P11 :JF, P12 :JF, P13 :JF, T <: Product :ClassManifest](construct: (P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12, P13) => T): RootJsonFormat[T] = {
    val Array(p1, p2, p3, p4, p5, p6, p7, p8, p9, p10, p11, p12, p13) = extractFieldNames(classManifest[T])
    jsonFormat(construct, p1, p2, p3, p4, p5, p6, p7, p8, p9, p10, p11, p12, p13)
  }
  def jsonFormat[P1 :JF, P2 :JF, P3 :JF, P4 :JF, P5 :JF, P6 :JF, P7 :JF, P8 :JF, P9 :JF, P10 :JF, P11 :JF, P12 :JF, P13 :JF, T <: Product](construct: (P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12, P13) => T, fieldName1: String, fieldName2: String, fieldName3: String, fieldName4: String, fieldName5: String, fieldName6: String, fieldName7: String, fieldName8: String, fieldName9: String, fieldName10: String, fieldName11: String, fieldName12: String, fieldName13: String): RootJsonFormat[T] = new RootJsonFormat[T]{
    def write(p: T) = {
      val fields = new collection.mutable.ListBuffer[(String, JsValue)]
      fields.sizeHint(13 * 2)
      fields ++= productElement2Field[P1](fieldName1, p, 0)
      fields ++= productElement2Field[P2](fieldName2, p, 1)
      fields ++= productElement2Field[P3](fieldName3, p, 2)
      fields ++= productElement2Field[P4](fieldName4, p, 3)
      fields ++= productElement2Field[P5](fieldName5, p, 4)
      fields ++= productElement2Field[P6](fieldName6, p, 5)
      fields ++= productElement2Field[P7](fieldName7, p, 6)
      fields ++= productElement2Field[P8](fieldName8, p, 7)
      fields ++= productElement2Field[P9](fieldName9, p, 8)
      fields ++= productElement2Field[P10](fieldName10, p, 9)
      fields ++= productElement2Field[P11](fieldName11, p, 10)
      fields ++= productElement2Field[P12](fieldName12, p, 11)
      fields ++= productElement2Field[P13](fieldName13, p, 12)
      JsObject(fields: _*)
    }
    def read(value: JsValue) = {
      val p1V = fromField[P1](value, fieldName1)
      val p2V = fromField[P2](value, fieldName2)
      val p3V = fromField[P3](value, fieldName3)
      val p4V = fromField[P4](value, fieldName4)
      val p5V = fromField[P5](value, fieldName5)
      val p6V = fromField[P6](value, fieldName6)
      val p7V = fromField[P7](value, fieldName7)
      val p8V = fromField[P8](value, fieldName8)
      val p9V = fromField[P9](value, fieldName9)
      val p10V = fromField[P10](value, fieldName10)
      val p11V = fromField[P11](value, fieldName11)
      val p12V = fromField[P12](value, fieldName12)
      val p13V = fromField[P13](value, fieldName13)
      construct(p1V, p2V, p3V, p4V, p5V, p6V, p7V, p8V, p9V, p10V, p11V, p12V, p13V)
    }
  }


  // Case classes with 14 parameters

  def jsonFormat14[P1 :JF, P2 :JF, P3 :JF, P4 :JF, P5 :JF, P6 :JF, P7 :JF, P8 :JF, P9 :JF, P10 :JF, P11 :JF, P12 :JF, P13 :JF, P14 :JF, T <: Product :ClassManifest](construct: (P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12, P13, P14) => T): RootJsonFormat[T] = {
    val Array(p1, p2, p3, p4, p5, p6, p7, p8, p9, p10, p11, p12, p13, p14) = extractFieldNames(classManifest[T])
    jsonFormat(construct, p1, p2, p3, p4, p5, p6, p7, p8, p9, p10, p11, p12, p13, p14)
  }
  def jsonFormat[P1 :JF, P2 :JF, P3 :JF, P4 :JF, P5 :JF, P6 :JF, P7 :JF, P8 :JF, P9 :JF, P10 :JF, P11 :JF, P12 :JF, P13 :JF, P14 :JF, T <: Product](construct: (P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12, P13, P14) => T, fieldName1: String, fieldName2: String, fieldName3: String, fieldName4: String, fieldName5: String, fieldName6: String, fieldName7: String, fieldName8: String, fieldName9: String, fieldName10: String, fieldName11: String, fieldName12: String, fieldName13: String, fieldName14: String): RootJsonFormat[T] = new RootJsonFormat[T]{
    def write(p: T) = {
      val fields = new collection.mutable.ListBuffer[(String, JsValue)]
      fields.sizeHint(14 * 2)
      fields ++= productElement2Field[P1](fieldName1, p, 0)
      fields ++= productElement2Field[P2](fieldName2, p, 1)
      fields ++= productElement2Field[P3](fieldName3, p, 2)
      fields ++= productElement2Field[P4](fieldName4, p, 3)
      fields ++= productElement2Field[P5](fieldName5, p, 4)
      fields ++= productElement2Field[P6](fieldName6, p, 5)
      fields ++= productElement2Field[P7](fieldName7, p, 6)
      fields ++= productElement2Field[P8](fieldName8, p, 7)
      fields ++= productElement2Field[P9](fieldName9, p, 8)
      fields ++= productElement2Field[P10](fieldName10, p, 9)
      fields ++= productElement2Field[P11](fieldName11, p, 10)
      fields ++= productElement2Field[P12](fieldName12, p, 11)
      fields ++= productElement2Field[P13](fieldName13, p, 12)
      fields ++= productElement2Field[P14](fieldName14, p, 13)
      JsObject(fields: _*)
    }
    def read(value: JsValue) = {
      val p1V = fromField[P1](value, fieldName1)
      val p2V = fromField[P2](value, fieldName2)
      val p3V = fromField[P3](value, fieldName3)
      val p4V = fromField[P4](value, fieldName4)
      val p5V = fromField[P5](value, fieldName5)
      val p6V = fromField[P6](value, fieldName6)
      val p7V = fromField[P7](value, fieldName7)
      val p8V = fromField[P8](value, fieldName8)
      val p9V = fromField[P9](value, fieldName9)
      val p10V = fromField[P10](value, fieldName10)
      val p11V = fromField[P11](value, fieldName11)
      val p12V = fromField[P12](value, fieldName12)
      val p13V = fromField[P13](value, fieldName13)
      val p14V = fromField[P14](value, fieldName14)
      construct(p1V, p2V, p3V, p4V, p5V, p6V, p7V, p8V, p9V, p10V, p11V, p12V, p13V, p14V)
    }
  }


  // Case classes with 15 parameters

  def jsonFormat15[P1 :JF, P2 :JF, P3 :JF, P4 :JF, P5 :JF, P6 :JF, P7 :JF, P8 :JF, P9 :JF, P10 :JF, P11 :JF, P12 :JF, P13 :JF, P14 :JF, P15 :JF, T <: Product :ClassManifest](construct: (P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12, P13, P14, P15) => T): RootJsonFormat[T] = {
    val Array(p1, p2, p3, p4, p5, p6, p7, p8, p9, p10, p11, p12, p13, p14, p15) = extractFieldNames(classManifest[T])
    jsonFormat(construct, p1, p2, p3, p4, p5, p6, p7, p8, p9, p10, p11, p12, p13, p14, p15)
  }
  def jsonFormat[P1 :JF, P2 :JF, P3 :JF, P4 :JF, P5 :JF, P6 :JF, P7 :JF, P8 :JF, P9 :JF, P10 :JF, P11 :JF, P12 :JF, P13 :JF, P14 :JF, P15 :JF, T <: Product](construct: (P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12, P13, P14, P15) => T, fieldName1: String, fieldName2: String, fieldName3: String, fieldName4: String, fieldName5: String, fieldName6: String, fieldName7: String, fieldName8: String, fieldName9: String, fieldName10: String, fieldName11: String, fieldName12: String, fieldName13: String, fieldName14: String, fieldName15: String): RootJsonFormat[T] = new RootJsonFormat[T]{
    def write(p: T) = {
      val fields = new collection.mutable.ListBuffer[(String, JsValue)]
      fields.sizeHint(15 * 2)
      fields ++= productElement2Field[P1](fieldName1, p, 0)
      fields ++= productElement2Field[P2](fieldName2, p, 1)
      fields ++= productElement2Field[P3](fieldName3, p, 2)
      fields ++= productElement2Field[P4](fieldName4, p, 3)
      fields ++= productElement2Field[P5](fieldName5, p, 4)
      fields ++= productElement2Field[P6](fieldName6, p, 5)
      fields ++= productElement2Field[P7](fieldName7, p, 6)
      fields ++= productElement2Field[P8](fieldName8, p, 7)
      fields ++= productElement2Field[P9](fieldName9, p, 8)
      fields ++= productElement2Field[P10](fieldName10, p, 9)
      fields ++= productElement2Field[P11](fieldName11, p, 10)
      fields ++= productElement2Field[P12](fieldName12, p, 11)
      fields ++= productElement2Field[P13](fieldName13, p, 12)
      fields ++= productElement2Field[P14](fieldName14, p, 13)
      fields ++= productElement2Field[P15](fieldName15, p, 14)
      JsObject(fields: _*)
    }
    def read(value: JsValue) = {
      val p1V = fromField[P1](value, fieldName1)
      val p2V = fromField[P2](value, fieldName2)
      val p3V = fromField[P3](value, fieldName3)
      val p4V = fromField[P4](value, fieldName4)
      val p5V = fromField[P5](value, fieldName5)
      val p6V = fromField[P6](value, fieldName6)
      val p7V = fromField[P7](value, fieldName7)
      val p8V = fromField[P8](value, fieldName8)
      val p9V = fromField[P9](value, fieldName9)
      val p10V = fromField[P10](value, fieldName10)
      val p11V = fromField[P11](value, fieldName11)
      val p12V = fromField[P12](value, fieldName12)
      val p13V = fromField[P13](value, fieldName13)
      val p14V = fromField[P14](value, fieldName14)
      val p15V = fromField[P15](value, fieldName15)
      construct(p1V, p2V, p3V, p4V, p5V, p6V, p7V, p8V, p9V, p10V, p11V, p12V, p13V, p14V, p15V)
    }
  }


  // Case classes with 16 parameters

  def jsonFormat16[P1 :JF, P2 :JF, P3 :JF, P4 :JF, P5 :JF, P6 :JF, P7 :JF, P8 :JF, P9 :JF, P10 :JF, P11 :JF, P12 :JF, P13 :JF, P14 :JF, P15 :JF, P16 :JF, T <: Product :ClassManifest](construct: (P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12, P13, P14, P15, P16) => T): RootJsonFormat[T] = {
    val Array(p1, p2, p3, p4, p5, p6, p7, p8, p9, p10, p11, p12, p13, p14, p15, p16) = extractFieldNames(classManifest[T])
    jsonFormat(construct, p1, p2, p3, p4, p5, p6, p7, p8, p9, p10, p11, p12, p13, p14, p15, p16)
  }
  def jsonFormat[P1 :JF, P2 :JF, P3 :JF, P4 :JF, P5 :JF, P6 :JF, P7 :JF, P8 :JF, P9 :JF, P10 :JF, P11 :JF, P12 :JF, P13 :JF, P14 :JF, P15 :JF, P16 :JF, T <: Product](construct: (P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12, P13, P14, P15, P16) => T, fieldName1: String, fieldName2: String, fieldName3: String, fieldName4: String, fieldName5: String, fieldName6: String, fieldName7: String, fieldName8: String, fieldName9: String, fieldName10: String, fieldName11: String, fieldName12: String, fieldName13: String, fieldName14: String, fieldName15: String, fieldName16: String): RootJsonFormat[T] = new RootJsonFormat[T]{
    def write(p: T) = {
      val fields = new collection.mutable.ListBuffer[(String, JsValue)]
      fields.sizeHint(16 * 2)
      fields ++= productElement2Field[P1](fieldName1, p, 0)
      fields ++= productElement2Field[P2](fieldName2, p, 1)
      fields ++= productElement2Field[P3](fieldName3, p, 2)
      fields ++= productElement2Field[P4](fieldName4, p, 3)
      fields ++= productElement2Field[P5](fieldName5, p, 4)
      fields ++= productElement2Field[P6](fieldName6, p, 5)
      fields ++= productElement2Field[P7](fieldName7, p, 6)
      fields ++= productElement2Field[P8](fieldName8, p, 7)
      fields ++= productElement2Field[P9](fieldName9, p, 8)
      fields ++= productElement2Field[P10](fieldName10, p, 9)
      fields ++= productElement2Field[P11](fieldName11, p, 10)
      fields ++= productElement2Field[P12](fieldName12, p, 11)
      fields ++= productElement2Field[P13](fieldName13, p, 12)
      fields ++= productElement2Field[P14](fieldName14, p, 13)
      fields ++= productElement2Field[P15](fieldName15, p, 14)
      fields ++= productElement2Field[P16](fieldName16, p, 15)
      JsObject(fields: _*)
    }
    def read(value: JsValue) = {
      val p1V = fromField[P1](value, fieldName1)
      val p2V = fromField[P2](value, fieldName2)
      val p3V = fromField[P3](value, fieldName3)
      val p4V = fromField[P4](value, fieldName4)
      val p5V = fromField[P5](value, fieldName5)
      val p6V = fromField[P6](value, fieldName6)
      val p7V = fromField[P7](value, fieldName7)
      val p8V = fromField[P8](value, fieldName8)
      val p9V = fromField[P9](value, fieldName9)
      val p10V = fromField[P10](value, fieldName10)
      val p11V = fromField[P11](value, fieldName11)
      val p12V = fromField[P12](value, fieldName12)
      val p13V = fromField[P13](value, fieldName13)
      val p14V = fromField[P14](value, fieldName14)
      val p15V = fromField[P15](value, fieldName15)
      val p16V = fromField[P16](value, fieldName16)
      construct(p1V, p2V, p3V, p4V, p5V, p6V, p7V, p8V, p9V, p10V, p11V, p12V, p13V, p14V, p15V, p16V)
    }
  }


  // Case classes with 17 parameters

  def jsonFormat17[P1 :JF, P2 :JF, P3 :JF, P4 :JF, P5 :JF, P6 :JF, P7 :JF, P8 :JF, P9 :JF, P10 :JF, P11 :JF, P12 :JF, P13 :JF, P14 :JF, P15 :JF, P16 :JF, P17 :JF, T <: Product :ClassManifest](construct: (P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12, P13, P14, P15, P16, P17) => T): RootJsonFormat[T] = {
    val Array(p1, p2, p3, p4, p5, p6, p7, p8, p9, p10, p11, p12, p13, p14, p15, p16, p17) = extractFieldNames(classManifest[T])
    jsonFormat(construct, p1, p2, p3, p4, p5, p6, p7, p8, p9, p10, p11, p12, p13, p14, p15, p16, p17)
  }
  def jsonFormat[P1 :JF, P2 :JF, P3 :JF, P4 :JF, P5 :JF, P6 :JF, P7 :JF, P8 :JF, P9 :JF, P10 :JF, P11 :JF, P12 :JF, P13 :JF, P14 :JF, P15 :JF, P16 :JF, P17 :JF, T <: Product](construct: (P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12, P13, P14, P15, P16, P17) => T, fieldName1: String, fieldName2: String, fieldName3: String, fieldName4: String, fieldName5: String, fieldName6: String, fieldName7: String, fieldName8: String, fieldName9: String, fieldName10: String, fieldName11: String, fieldName12: String, fieldName13: String, fieldName14: String, fieldName15: String, fieldName16: String, fieldName17: String): RootJsonFormat[T] = new RootJsonFormat[T]{
    def write(p: T) = {
      val fields = new collection.mutable.ListBuffer[(String, JsValue)]
      fields.sizeHint(17 * 2)
      fields ++= productElement2Field[P1](fieldName1, p, 0)
      fields ++= productElement2Field[P2](fieldName2, p, 1)
      fields ++= productElement2Field[P3](fieldName3, p, 2)
      fields ++= productElement2Field[P4](fieldName4, p, 3)
      fields ++= productElement2Field[P5](fieldName5, p, 4)
      fields ++= productElement2Field[P6](fieldName6, p, 5)
      fields ++= productElement2Field[P7](fieldName7, p, 6)
      fields ++= productElement2Field[P8](fieldName8, p, 7)
      fields ++= productElement2Field[P9](fieldName9, p, 8)
      fields ++= productElement2Field[P10](fieldName10, p, 9)
      fields ++= productElement2Field[P11](fieldName11, p, 10)
      fields ++= productElement2Field[P12](fieldName12, p, 11)
      fields ++= productElement2Field[P13](fieldName13, p, 12)
      fields ++= productElement2Field[P14](fieldName14, p, 13)
      fields ++= productElement2Field[P15](fieldName15, p, 14)
      fields ++= productElement2Field[P16](fieldName16, p, 15)
      fields ++= productElement2Field[P17](fieldName17, p, 16)
      JsObject(fields: _*)
    }
    def read(value: JsValue) = {
      val p1V = fromField[P1](value, fieldName1)
      val p2V = fromField[P2](value, fieldName2)
      val p3V = fromField[P3](value, fieldName3)
      val p4V = fromField[P4](value, fieldName4)
      val p5V = fromField[P5](value, fieldName5)
      val p6V = fromField[P6](value, fieldName6)
      val p7V = fromField[P7](value, fieldName7)
      val p8V = fromField[P8](value, fieldName8)
      val p9V = fromField[P9](value, fieldName9)
      val p10V = fromField[P10](value, fieldName10)
      val p11V = fromField[P11](value, fieldName11)
      val p12V = fromField[P12](value, fieldName12)
      val p13V = fromField[P13](value, fieldName13)
      val p14V = fromField[P14](value, fieldName14)
      val p15V = fromField[P15](value, fieldName15)
      val p16V = fromField[P16](value, fieldName16)
      val p17V = fromField[P17](value, fieldName17)
      construct(p1V, p2V, p3V, p4V, p5V, p6V, p7V, p8V, p9V, p10V, p11V, p12V, p13V, p14V, p15V, p16V, p17V)
    }
  }


  // Case classes with 18 parameters

  def jsonFormat18[P1 :JF, P2 :JF, P3 :JF, P4 :JF, P5 :JF, P6 :JF, P7 :JF, P8 :JF, P9 :JF, P10 :JF, P11 :JF, P12 :JF, P13 :JF, P14 :JF, P15 :JF, P16 :JF, P17 :JF, P18 :JF, T <: Product :ClassManifest](construct: (P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12, P13, P14, P15, P16, P17, P18) => T): RootJsonFormat[T] = {
    val Array(p1, p2, p3, p4, p5, p6, p7, p8, p9, p10, p11, p12, p13, p14, p15, p16, p17, p18) = extractFieldNames(classManifest[T])
    jsonFormat(construct, p1, p2, p3, p4, p5, p6, p7, p8, p9, p10, p11, p12, p13, p14, p15, p16, p17, p18)
  }
  def jsonFormat[P1 :JF, P2 :JF, P3 :JF, P4 :JF, P5 :JF, P6 :JF, P7 :JF, P8 :JF, P9 :JF, P10 :JF, P11 :JF, P12 :JF, P13 :JF, P14 :JF, P15 :JF, P16 :JF, P17 :JF, P18 :JF, T <: Product](construct: (P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12, P13, P14, P15, P16, P17, P18) => T, fieldName1: String, fieldName2: String, fieldName3: String, fieldName4: String, fieldName5: String, fieldName6: String, fieldName7: String, fieldName8: String, fieldName9: String, fieldName10: String, fieldName11: String, fieldName12: String, fieldName13: String, fieldName14: String, fieldName15: String, fieldName16: String, fieldName17: String, fieldName18: String): RootJsonFormat[T] = new RootJsonFormat[T]{
    def write(p: T) = {
      val fields = new collection.mutable.ListBuffer[(String, JsValue)]
      fields.sizeHint(18 * 2)
      fields ++= productElement2Field[P1](fieldName1, p, 0)
      fields ++= productElement2Field[P2](fieldName2, p, 1)
      fields ++= productElement2Field[P3](fieldName3, p, 2)
      fields ++= productElement2Field[P4](fieldName4, p, 3)
      fields ++= productElement2Field[P5](fieldName5, p, 4)
      fields ++= productElement2Field[P6](fieldName6, p, 5)
      fields ++= productElement2Field[P7](fieldName7, p, 6)
      fields ++= productElement2Field[P8](fieldName8, p, 7)
      fields ++= productElement2Field[P9](fieldName9, p, 8)
      fields ++= productElement2Field[P10](fieldName10, p, 9)
      fields ++= productElement2Field[P11](fieldName11, p, 10)
      fields ++= productElement2Field[P12](fieldName12, p, 11)
      fields ++= productElement2Field[P13](fieldName13, p, 12)
      fields ++= productElement2Field[P14](fieldName14, p, 13)
      fields ++= productElement2Field[P15](fieldName15, p, 14)
      fields ++= productElement2Field[P16](fieldName16, p, 15)
      fields ++= productElement2Field[P17](fieldName17, p, 16)
      fields ++= productElement2Field[P18](fieldName18, p, 17)
      JsObject(fields: _*)
    }
    def read(value: JsValue) = {
      val p1V = fromField[P1](value, fieldName1)
      val p2V = fromField[P2](value, fieldName2)
      val p3V = fromField[P3](value, fieldName3)
      val p4V = fromField[P4](value, fieldName4)
      val p5V = fromField[P5](value, fieldName5)
      val p6V = fromField[P6](value, fieldName6)
      val p7V = fromField[P7](value, fieldName7)
      val p8V = fromField[P8](value, fieldName8)
      val p9V = fromField[P9](value, fieldName9)
      val p10V = fromField[P10](value, fieldName10)
      val p11V = fromField[P11](value, fieldName11)
      val p12V = fromField[P12](value, fieldName12)
      val p13V = fromField[P13](value, fieldName13)
      val p14V = fromField[P14](value, fieldName14)
      val p15V = fromField[P15](value, fieldName15)
      val p16V = fromField[P16](value, fieldName16)
      val p17V = fromField[P17](value, fieldName17)
      val p18V = fromField[P18](value, fieldName18)
      construct(p1V, p2V, p3V, p4V, p5V, p6V, p7V, p8V, p9V, p10V, p11V, p12V, p13V, p14V, p15V, p16V, p17V, p18V)
    }
  }


  // Case classes with 19 parameters

  def jsonFormat19[P1 :JF, P2 :JF, P3 :JF, P4 :JF, P5 :JF, P6 :JF, P7 :JF, P8 :JF, P9 :JF, P10 :JF, P11 :JF, P12 :JF, P13 :JF, P14 :JF, P15 :JF, P16 :JF, P17 :JF, P18 :JF, P19 :JF, T <: Product :ClassManifest](construct: (P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12, P13, P14, P15, P16, P17, P18, P19) => T): RootJsonFormat[T] = {
    val Array(p1, p2, p3, p4, p5, p6, p7, p8, p9, p10, p11, p12, p13, p14, p15, p16, p17, p18, p19) = extractFieldNames(classManifest[T])
    jsonFormat(construct, p1, p2, p3, p4, p5, p6, p7, p8, p9, p10, p11, p12, p13, p14, p15, p16, p17, p18, p19)
  }
  def jsonFormat[P1 :JF, P2 :JF, P3 :JF, P4 :JF, P5 :JF, P6 :JF, P7 :JF, P8 :JF, P9 :JF, P10 :JF, P11 :JF, P12 :JF, P13 :JF, P14 :JF, P15 :JF, P16 :JF, P17 :JF, P18 :JF, P19 :JF, T <: Product](construct: (P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12, P13, P14, P15, P16, P17, P18, P19) => T, fieldName1: String, fieldName2: String, fieldName3: String, fieldName4: String, fieldName5: String, fieldName6: String, fieldName7: String, fieldName8: String, fieldName9: String, fieldName10: String, fieldName11: String, fieldName12: String, fieldName13: String, fieldName14: String, fieldName15: String, fieldName16: String, fieldName17: String, fieldName18: String, fieldName19: String): RootJsonFormat[T] = new RootJsonFormat[T]{
    def write(p: T) = {
      val fields = new collection.mutable.ListBuffer[(String, JsValue)]
      fields.sizeHint(19 * 2)
      fields ++= productElement2Field[P1](fieldName1, p, 0)
      fields ++= productElement2Field[P2](fieldName2, p, 1)
      fields ++= productElement2Field[P3](fieldName3, p, 2)
      fields ++= productElement2Field[P4](fieldName4, p, 3)
      fields ++= productElement2Field[P5](fieldName5, p, 4)
      fields ++= productElement2Field[P6](fieldName6, p, 5)
      fields ++= productElement2Field[P7](fieldName7, p, 6)
      fields ++= productElement2Field[P8](fieldName8, p, 7)
      fields ++= productElement2Field[P9](fieldName9, p, 8)
      fields ++= productElement2Field[P10](fieldName10, p, 9)
      fields ++= productElement2Field[P11](fieldName11, p, 10)
      fields ++= productElement2Field[P12](fieldName12, p, 11)
      fields ++= productElement2Field[P13](fieldName13, p, 12)
      fields ++= productElement2Field[P14](fieldName14, p, 13)
      fields ++= productElement2Field[P15](fieldName15, p, 14)
      fields ++= productElement2Field[P16](fieldName16, p, 15)
      fields ++= productElement2Field[P17](fieldName17, p, 16)
      fields ++= productElement2Field[P18](fieldName18, p, 17)
      fields ++= productElement2Field[P19](fieldName19, p, 18)
      JsObject(fields: _*)
    }
    def read(value: JsValue) = {
      val p1V = fromField[P1](value, fieldName1)
      val p2V = fromField[P2](value, fieldName2)
      val p3V = fromField[P3](value, fieldName3)
      val p4V = fromField[P4](value, fieldName4)
      val p5V = fromField[P5](value, fieldName5)
      val p6V = fromField[P6](value, fieldName6)
      val p7V = fromField[P7](value, fieldName7)
      val p8V = fromField[P8](value, fieldName8)
      val p9V = fromField[P9](value, fieldName9)
      val p10V = fromField[P10](value, fieldName10)
      val p11V = fromField[P11](value, fieldName11)
      val p12V = fromField[P12](value, fieldName12)
      val p13V = fromField[P13](value, fieldName13)
      val p14V = fromField[P14](value, fieldName14)
      val p15V = fromField[P15](value, fieldName15)
      val p16V = fromField[P16](value, fieldName16)
      val p17V = fromField[P17](value, fieldName17)
      val p18V = fromField[P18](value, fieldName18)
      val p19V = fromField[P19](value, fieldName19)
      construct(p1V, p2V, p3V, p4V, p5V, p6V, p7V, p8V, p9V, p10V, p11V, p12V, p13V, p14V, p15V, p16V, p17V, p18V, p19V)
    }
  }


  // Case classes with 20 parameters

  def jsonFormat20[P1 :JF, P2 :JF, P3 :JF, P4 :JF, P5 :JF, P6 :JF, P7 :JF, P8 :JF, P9 :JF, P10 :JF, P11 :JF, P12 :JF, P13 :JF, P14 :JF, P15 :JF, P16 :JF, P17 :JF, P18 :JF, P19 :JF, P20 :JF, T <: Product :ClassManifest](construct: (P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12, P13, P14, P15, P16, P17, P18, P19, P20) => T): RootJsonFormat[T] = {
    val Array(p1, p2, p3, p4, p5, p6, p7, p8, p9, p10, p11, p12, p13, p14, p15, p16, p17, p18, p19, p20) = extractFieldNames(classManifest[T])
    jsonFormat(construct, p1, p2, p3, p4, p5, p6, p7, p8, p9, p10, p11, p12, p13, p14, p15, p16, p17, p18, p19, p20)
  }
  def jsonFormat[P1 :JF, P2 :JF, P3 :JF, P4 :JF, P5 :JF, P6 :JF, P7 :JF, P8 :JF, P9 :JF, P10 :JF, P11 :JF, P12 :JF, P13 :JF, P14 :JF, P15 :JF, P16 :JF, P17 :JF, P18 :JF, P19 :JF, P20 :JF, T <: Product](construct: (P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12, P13, P14, P15, P16, P17, P18, P19, P20) => T, fieldName1: String, fieldName2: String, fieldName3: String, fieldName4: String, fieldName5: String, fieldName6: String, fieldName7: String, fieldName8: String, fieldName9: String, fieldName10: String, fieldName11: String, fieldName12: String, fieldName13: String, fieldName14: String, fieldName15: String, fieldName16: String, fieldName17: String, fieldName18: String, fieldName19: String, fieldName20: String): RootJsonFormat[T] = new RootJsonFormat[T]{
    def write(p: T) = {
      val fields = new collection.mutable.ListBuffer[(String, JsValue)]
      fields.sizeHint(20 * 2)
      fields ++= productElement2Field[P1](fieldName1, p, 0)
      fields ++= productElement2Field[P2](fieldName2, p, 1)
      fields ++= productElement2Field[P3](fieldName3, p, 2)
      fields ++= productElement2Field[P4](fieldName4, p, 3)
      fields ++= productElement2Field[P5](fieldName5, p, 4)
      fields ++= productElement2Field[P6](fieldName6, p, 5)
      fields ++= productElement2Field[P7](fieldName7, p, 6)
      fields ++= productElement2Field[P8](fieldName8, p, 7)
      fields ++= productElement2Field[P9](fieldName9, p, 8)
      fields ++= productElement2Field[P10](fieldName10, p, 9)
      fields ++= productElement2Field[P11](fieldName11, p, 10)
      fields ++= productElement2Field[P12](fieldName12, p, 11)
      fields ++= productElement2Field[P13](fieldName13, p, 12)
      fields ++= productElement2Field[P14](fieldName14, p, 13)
      fields ++= productElement2Field[P15](fieldName15, p, 14)
      fields ++= productElement2Field[P16](fieldName16, p, 15)
      fields ++= productElement2Field[P17](fieldName17, p, 16)
      fields ++= productElement2Field[P18](fieldName18, p, 17)
      fields ++= productElement2Field[P19](fieldName19, p, 18)
      fields ++= productElement2Field[P20](fieldName20, p, 19)
      JsObject(fields: _*)
    }
    def read(value: JsValue) = {
      val p1V = fromField[P1](value, fieldName1)
      val p2V = fromField[P2](value, fieldName2)
      val p3V = fromField[P3](value, fieldName3)
      val p4V = fromField[P4](value, fieldName4)
      val p5V = fromField[P5](value, fieldName5)
      val p6V = fromField[P6](value, fieldName6)
      val p7V = fromField[P7](value, fieldName7)
      val p8V = fromField[P8](value, fieldName8)
      val p9V = fromField[P9](value, fieldName9)
      val p10V = fromField[P10](value, fieldName10)
      val p11V = fromField[P11](value, fieldName11)
      val p12V = fromField[P12](value, fieldName12)
      val p13V = fromField[P13](value, fieldName13)
      val p14V = fromField[P14](value, fieldName14)
      val p15V = fromField[P15](value, fieldName15)
      val p16V = fromField[P16](value, fieldName16)
      val p17V = fromField[P17](value, fieldName17)
      val p18V = fromField[P18](value, fieldName18)
      val p19V = fromField[P19](value, fieldName19)
      val p20V = fromField[P20](value, fieldName20)
      construct(p1V, p2V, p3V, p4V, p5V, p6V, p7V, p8V, p9V, p10V, p11V, p12V, p13V, p14V, p15V, p16V, p17V, p18V, p19V, p20V)
    }
  }


  // Case classes with 21 parameters

  def jsonFormat21[P1 :JF, P2 :JF, P3 :JF, P4 :JF, P5 :JF, P6 :JF, P7 :JF, P8 :JF, P9 :JF, P10 :JF, P11 :JF, P12 :JF, P13 :JF, P14 :JF, P15 :JF, P16 :JF, P17 :JF, P18 :JF, P19 :JF, P20 :JF, P21 :JF, T <: Product :ClassManifest](construct: (P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12, P13, P14, P15, P16, P17, P18, P19, P20, P21) => T): RootJsonFormat[T] = {
    val Array(p1, p2, p3, p4, p5, p6, p7, p8, p9, p10, p11, p12, p13, p14, p15, p16, p17, p18, p19, p20, p21) = extractFieldNames(classManifest[T])
    jsonFormat(construct, p1, p2, p3, p4, p5, p6, p7, p8, p9, p10, p11, p12, p13, p14, p15, p16, p17, p18, p19, p20, p21)
  }
  def jsonFormat[P1 :JF, P2 :JF, P3 :JF, P4 :JF, P5 :JF, P6 :JF, P7 :JF, P8 :JF, P9 :JF, P10 :JF, P11 :JF, P12 :JF, P13 :JF, P14 :JF, P15 :JF, P16 :JF, P17 :JF, P18 :JF, P19 :JF, P20 :JF, P21 :JF, T <: Product](construct: (P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12, P13, P14, P15, P16, P17, P18, P19, P20, P21) => T, fieldName1: String, fieldName2: String, fieldName3: String, fieldName4: String, fieldName5: String, fieldName6: String, fieldName7: String, fieldName8: String, fieldName9: String, fieldName10: String, fieldName11: String, fieldName12: String, fieldName13: String, fieldName14: String, fieldName15: String, fieldName16: String, fieldName17: String, fieldName18: String, fieldName19: String, fieldName20: String, fieldName21: String): RootJsonFormat[T] = new RootJsonFormat[T]{
    def write(p: T) = {
      val fields = new collection.mutable.ListBuffer[(String, JsValue)]
      fields.sizeHint(21 * 2)
      fields ++= productElement2Field[P1](fieldName1, p, 0)
      fields ++= productElement2Field[P2](fieldName2, p, 1)
      fields ++= productElement2Field[P3](fieldName3, p, 2)
      fields ++= productElement2Field[P4](fieldName4, p, 3)
      fields ++= productElement2Field[P5](fieldName5, p, 4)
      fields ++= productElement2Field[P6](fieldName6, p, 5)
      fields ++= productElement2Field[P7](fieldName7, p, 6)
      fields ++= productElement2Field[P8](fieldName8, p, 7)
      fields ++= productElement2Field[P9](fieldName9, p, 8)
      fields ++= productElement2Field[P10](fieldName10, p, 9)
      fields ++= productElement2Field[P11](fieldName11, p, 10)
      fields ++= productElement2Field[P12](fieldName12, p, 11)
      fields ++= productElement2Field[P13](fieldName13, p, 12)
      fields ++= productElement2Field[P14](fieldName14, p, 13)
      fields ++= productElement2Field[P15](fieldName15, p, 14)
      fields ++= productElement2Field[P16](fieldName16, p, 15)
      fields ++= productElement2Field[P17](fieldName17, p, 16)
      fields ++= productElement2Field[P18](fieldName18, p, 17)
      fields ++= productElement2Field[P19](fieldName19, p, 18)
      fields ++= productElement2Field[P20](fieldName20, p, 19)
      fields ++= productElement2Field[P21](fieldName21, p, 20)
      JsObject(fields: _*)
    }
    def read(value: JsValue) = {
      val p1V = fromField[P1](value, fieldName1)
      val p2V = fromField[P2](value, fieldName2)
      val p3V = fromField[P3](value, fieldName3)
      val p4V = fromField[P4](value, fieldName4)
      val p5V = fromField[P5](value, fieldName5)
      val p6V = fromField[P6](value, fieldName6)
      val p7V = fromField[P7](value, fieldName7)
      val p8V = fromField[P8](value, fieldName8)
      val p9V = fromField[P9](value, fieldName9)
      val p10V = fromField[P10](value, fieldName10)
      val p11V = fromField[P11](value, fieldName11)
      val p12V = fromField[P12](value, fieldName12)
      val p13V = fromField[P13](value, fieldName13)
      val p14V = fromField[P14](value, fieldName14)
      val p15V = fromField[P15](value, fieldName15)
      val p16V = fromField[P16](value, fieldName16)
      val p17V = fromField[P17](value, fieldName17)
      val p18V = fromField[P18](value, fieldName18)
      val p19V = fromField[P19](value, fieldName19)
      val p20V = fromField[P20](value, fieldName20)
      val p21V = fromField[P21](value, fieldName21)
      construct(p1V, p2V, p3V, p4V, p5V, p6V, p7V, p8V, p9V, p10V, p11V, p12V, p13V, p14V, p15V, p16V, p17V, p18V, p19V, p20V, p21V)
    }
  }


  // Case classes with 22 parameters

  def jsonFormat22[P1 :JF, P2 :JF, P3 :JF, P4 :JF, P5 :JF, P6 :JF, P7 :JF, P8 :JF, P9 :JF, P10 :JF, P11 :JF, P12 :JF, P13 :JF, P14 :JF, P15 :JF, P16 :JF, P17 :JF, P18 :JF, P19 :JF, P20 :JF, P21 :JF, P22 :JF, T <: Product :ClassManifest](construct: (P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12, P13, P14, P15, P16, P17, P18, P19, P20, P21, P22) => T): RootJsonFormat[T] = {
    val Array(p1, p2, p3, p4, p5, p6, p7, p8, p9, p10, p11, p12, p13, p14, p15, p16, p17, p18, p19, p20, p21, p22) = extractFieldNames(classManifest[T])
    jsonFormat(construct, p1, p2, p3, p4, p5, p6, p7, p8, p9, p10, p11, p12, p13, p14, p15, p16, p17, p18, p19, p20, p21, p22)
  }
  def jsonFormat[P1 :JF, P2 :JF, P3 :JF, P4 :JF, P5 :JF, P6 :JF, P7 :JF, P8 :JF, P9 :JF, P10 :JF, P11 :JF, P12 :JF, P13 :JF, P14 :JF, P15 :JF, P16 :JF, P17 :JF, P18 :JF, P19 :JF, P20 :JF, P21 :JF, P22 :JF, T <: Product](construct: (P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12, P13, P14, P15, P16, P17, P18, P19, P20, P21, P22) => T, fieldName1: String, fieldName2: String, fieldName3: String, fieldName4: String, fieldName5: String, fieldName6: String, fieldName7: String, fieldName8: String, fieldName9: String, fieldName10: String, fieldName11: String, fieldName12: String, fieldName13: String, fieldName14: String, fieldName15: String, fieldName16: String, fieldName17: String, fieldName18: String, fieldName19: String, fieldName20: String, fieldName21: String, fieldName22: String): RootJsonFormat[T] = new RootJsonFormat[T]{
    def write(p: T) = {
      val fields = new collection.mutable.ListBuffer[(String, JsValue)]
      fields.sizeHint(22 * 2)
      fields ++= productElement2Field[P1](fieldName1, p, 0)
      fields ++= productElement2Field[P2](fieldName2, p, 1)
      fields ++= productElement2Field[P3](fieldName3, p, 2)
      fields ++= productElement2Field[P4](fieldName4, p, 3)
      fields ++= productElement2Field[P5](fieldName5, p, 4)
      fields ++= productElement2Field[P6](fieldName6, p, 5)
      fields ++= productElement2Field[P7](fieldName7, p, 6)
      fields ++= productElement2Field[P8](fieldName8, p, 7)
      fields ++= productElement2Field[P9](fieldName9, p, 8)
      fields ++= productElement2Field[P10](fieldName10, p, 9)
      fields ++= productElement2Field[P11](fieldName11, p, 10)
      fields ++= productElement2Field[P12](fieldName12, p, 11)
      fields ++= productElement2Field[P13](fieldName13, p, 12)
      fields ++= productElement2Field[P14](fieldName14, p, 13)
      fields ++= productElement2Field[P15](fieldName15, p, 14)
      fields ++= productElement2Field[P16](fieldName16, p, 15)
      fields ++= productElement2Field[P17](fieldName17, p, 16)
      fields ++= productElement2Field[P18](fieldName18, p, 17)
      fields ++= productElement2Field[P19](fieldName19, p, 18)
      fields ++= productElement2Field[P20](fieldName20, p, 19)
      fields ++= productElement2Field[P21](fieldName21, p, 20)
      fields ++= productElement2Field[P22](fieldName22, p, 21)
      JsObject(fields: _*)
    }
    def read(value: JsValue) = {
      val p1V = fromField[P1](value, fieldName1)
      val p2V = fromField[P2](value, fieldName2)
      val p3V = fromField[P3](value, fieldName3)
      val p4V = fromField[P4](value, fieldName4)
      val p5V = fromField[P5](value, fieldName5)
      val p6V = fromField[P6](value, fieldName6)
      val p7V = fromField[P7](value, fieldName7)
      val p8V = fromField[P8](value, fieldName8)
      val p9V = fromField[P9](value, fieldName9)
      val p10V = fromField[P10](value, fieldName10)
      val p11V = fromField[P11](value, fieldName11)
      val p12V = fromField[P12](value, fieldName12)
      val p13V = fromField[P13](value, fieldName13)
      val p14V = fromField[P14](value, fieldName14)
      val p15V = fromField[P15](value, fieldName15)
      val p16V = fromField[P16](value, fieldName16)
      val p17V = fromField[P17](value, fieldName17)
      val p18V = fromField[P18](value, fieldName18)
      val p19V = fromField[P19](value, fieldName19)
      val p20V = fromField[P20](value, fieldName20)
      val p21V = fromField[P21](value, fieldName21)
      val p22V = fromField[P22](value, fieldName22)
      construct(p1V, p2V, p3V, p4V, p5V, p6V, p7V, p8V, p9V, p10V, p11V, p12V, p13V, p14V, p15V, p16V, p17V, p18V, p19V, p20V, p21V, p22V)
    }
  }
}
