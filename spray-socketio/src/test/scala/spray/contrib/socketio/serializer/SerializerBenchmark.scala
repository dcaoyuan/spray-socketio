package spray.contrib.socketio.serializer

import spray.contrib.socketio.benchmark.SimpleScalaBenchmark
import akka.util.ByteString
import com.google.caliper.{ Runner, Param }
import akka.serialization.{ Serializer, JavaSerializer }
import akka.actor.{ ExtendedActorSystem, ActorSystem }
import com.typesafe.config.ConfigFactory
import spray.contrib.socketio.ConnectionSession.{ OnFrame, SendEvent }

class SerializerBenchmark extends SimpleScalaBenchmark {

  @Param(Array("10", "100"))
  val length: Int = 0

  var array: Array[Int] = _

  val system = ActorSystem("test", ConfigFactory.parseString("")).asInstanceOf[ExtendedActorSystem]
  val javaSerializer = new JavaSerializer(system)
  val commandSerializer = new ConnectionSessionCommandSerializer(system)

  override def setUp() {
    // set up all your benchmark data here
    array = new Array(length)
  }

  def run(serializer: Serializer, obj: AnyRef) = {
    val bytes = serializer.toBinary(obj)
    val result = serializer.fromBinary(bytes, Some(obj.getClass))
    result // always have your snippet return a value that cannot easily be "optimized away"
  }

  def timeOnFrameSerializer(reps: Int) = repeat(reps) {
    run(commandSerializer, OnFrame("123456", ByteString("hello world")))
  }

  def timeOnFrameJavaSerializer(reps: Int) = repeat(reps) {
    run(javaSerializer, OnFrame("123456", ByteString("hello world")))
  }

  def timeSendEventSerializer(reps: Int) = repeat(reps) {
    run(commandSerializer, SendEvent("123456", "chat", "string", Left("hello world")))
  }

  def timeSendEventJavaSerializer(reps: Int) = repeat(reps) {
    run(javaSerializer, SendEvent("123456", "chat", "string", Left("hello world")))
  }
}

object SerializerBenchmarkRunner {

  def main(args: Array[String]) {
    Runner.main(classOf[SerializerBenchmark], args)
  }

}
