package spray.contrib.socketio.mq

import akka.actor.Props
import akka.stream.actor.ActorPublisher
import akka.stream.actor.ActorPublisherMessage
import scala.annotation.tailrec

/**
 * http://doc.akka.io/docs/akka-stream-and-http-experimental/1.0-RC2/scala/stream-integrations.html
 */
object Queue {
  def props[T]() = Props(classOf[Queue[T]])
}

class Queue[T] extends ActorPublisher[T] {
  private var buf = Vector.empty[T]

  def receive = {
    case ActorPublisherMessage.Request(_) =>
      deliverBuf()
    case ActorPublisherMessage.Cancel =>
      context.stop(self)
    case x: T @unchecked =>
      if (buf.isEmpty && totalDemand > 0) {
        onNext(x)
      } else {
        buf :+= x
        deliverBuf()
      }
  }

  @tailrec
  final def deliverBuf(): Unit = {
    if (totalDemand > 0) {
      /*
       * totalDemand is a Long and could be larger than
       * what buf.splitAt can accept
       */
      if (totalDemand <= Int.MaxValue) {
        val (use, keep) = buf.splitAt(totalDemand.toInt)
        buf = keep
        use foreach onNext
      } else {
        val (use, keep) = buf.splitAt(Int.MaxValue)
        buf = keep
        use foreach onNext
        deliverBuf()
      }
    }
  }
}
