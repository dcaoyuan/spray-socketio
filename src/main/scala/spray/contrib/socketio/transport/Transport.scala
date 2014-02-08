package spray.contrib.socketio.transport

object Transport {
  val idToTransport = Set(
    XhrPolling,
    XhrMultipart,
    HtmlFile,
    WebSocket,
    FlashSocket,
    JsonpPolling).map(x => x.id -> x).toMap

  def transportFor(id: String): Option[Transport] = idToTransport.get(id)
}

trait Transport {
  def id: String
}

object XhrPolling extends Transport {
  def id = "xhr-polling"
}

object XhrMultipart extends Transport {
  def id = "xhr-multipart"
}

object HtmlFile extends Transport {
  def id = "htmlfile"
}

object WebSocket extends Transport {
  def id = "websocket"
}

object FlashSocket extends Transport {
  def id = "flashsocket"
}

object JsonpPolling extends Transport {
  def id = "jsonp-polling"
}