package spray.contrib.socketio.mq

import akka.actor.Props
import akka.stream.actor.ActorPublisher
import akka.stream.actor.ActorPublisherMessage
import scala.annotation.tailrec

object Queue {
  def props() = Props(classOf[Queue])
}

class Queue extends ActorPublisher[Any] {
  private var buf = Vector.empty[Any]

  def receive = {
    case ActorPublisherMessage.Request(_) =>
      deliverBuf()
    case ActorPublisherMessage.Cancel =>
      context.stop(self)
    case x =>
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
