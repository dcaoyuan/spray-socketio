spray-socketio
==============

Socket.IO implementation on Spray with cluster sharding.

Supported transports : WebSocket, XHR-Polling.

.. image:: https://travis-ci.org/wandoulabs/spray-socketio.png
   :target: https://travis-ci.org/wandoulabs/spray-socketio
   :alt: spray-socketio build status


Benchmark
~~~~~~~~~

We did a simple load test on laptop (i7-2630QM 4xcore CPU @2.00GHz),
with Client/Server both run on it. It could process about 80k
messages/second under 50k long-live connections.

The test code could be found at
spray.contrib.socketio.examples.benchmark

To run cluster benchmark (0.1.x):

#.  Install cassandra and start it.
#.  sbt clean compile dist
#.  cd examples/socketio-benchmark/target/universal/
#.  unzip bench\_cluster-\*.zip
#.  cd bench\_cluster-xxxx/bin
#.  ./start\_cluster.sh tran 2551
#.  ./start\_cluster.sh conn1
#.  ./start\_cluster.sh conn2
#.  ./start\_cluster.sh busi
#.  ./start\_driver.sh
#. cd ../logs
#. tail -f driver\_rt.log

To run cluster benchmark (0.2.x):

#.  Install cassandra and start it.
#.  sbt clean compile dist
#.  cd examples/socketio-benchmark/target/universal/
#.  unzip bench\_cluster-\*.zip
#.  cd bench\_cluster-xxxx/bin
#.  ./start\_cluster.sh sess1 2551
#.  ./start\_cluster.sh sess2
#.  ./start\_cluster.sh topic1
#.  ./start\_cluster.sh tran
#.  ./start\_cluster.sh busi
#. ./start\_driver.sh
#. cd ../logs
#. tail -f rt\_driver.log

Since spray-socketio is under heavy developing, with the spray-socketio
version changed or snapshot version, you may need to cleanup cassandra
by:

.. code:: shell

  cqlsh
  cqlsh> select * from system.schema_keyspaces;
  cqlsh> drop keyspace akka;
  cqlsh> drop keyspace akka_snapshot;
  cqlsh> quit;

Usage
~~~~~

The artifact is published to Sonatype, so in order to use it you just
have to add the following dependency:

.. code:: scala

    resolvers += "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots"

    resolvers += "Spray" at "http://repo.spray.io"

    libraryDependencies += "com.wandoulabs.akka" %% "spray-socketio" % "0.1.3-SNAPSHOT"

Example
~~~~~~~

.. code:: scala

    package spray.contrib.socketio.examples

    import akka.io.IO
    import akka.actor.{ ActorSystem, Actor, Props, ActorLogging, ActorRef }
    import rx.lang.scala.Observable
    import rx.lang.scala.Observer
    import rx.lang.scala.Subject
    import spray.can.Http
    import spray.can.server.UHttp
    import spray.can.websocket.frame.Frame
    import spray.contrib.socketio.SocketIOExtension
    import spray.contrib.socketio.SocketIOServerWorker
    import spray.contrib.socketio.packet.EventPacket
    import spray.contrib.socketio.namespace.Namespace
    import spray.contrib.socketio.namespace.Namespace.{ OnConnect, OnDisconnect, OnData, OnEvent }
    import spray.contrib.socketio.namespace.NamespaceExtension
    import spray.http.{ HttpMethods, Uri, HttpEntity, ContentType, MediaTypes }
    import spray.http.HttpRequest
    import spray.http.HttpResponse
    import spray.json.DefaultJsonProtocol

    object SimpleServer extends App with MySslConfiguration {

      object SocketIOServer {
        def props(resovler: ActorRef) = Props(classOf[SocketIOServer], resolver)
      }
      class SocketIOServer(resolver: ActorRef) extends Actor with ActorLogging {
        def receive = {
          // when a new connection comes in we register a SocketIOWorker actor as the per connection handler
          case Http.Connected(remoteAddress, localAddress) =>
            val serverConnection = sender()
            val conn = context.actorOf(SocketIOWorker.props(serverConnection, resolver))
            serverConnection ! Http.Register(conn)
        }
      }

      val WEB_ROOT = "/home/dcaoyuan/myprjs/spray-socketio/src/main/scala/spray/contrib/socketio/examples"

      object SocketIOWorker {
        def props(serverConnection: ActorRef, resolver: ActorRef) = Props(classOf[SocketIOWorker], serverConnection, resolver)
      }
      class SocketIOWorker(val serverConnection: ActorRef, val resolver: ActorRef) extends Actor with SocketIOServerWorker {

        def genericLogic: Receive = {
          case HttpRequest(HttpMethods.GET, Uri.Path("/socketio.html"), _, _, _) =>
            val content = renderTextFile(WEB_ROOT + "/socketio.html")
            val entity = HttpEntity(ContentType(MediaTypes.`text/html`), content)
            sender() ! HttpResponse(entity = entity)

          case HttpRequest(HttpMethods.GET, Uri.Path("/jquery-1.7.2.min.js"), _, _, _) =>
            val content = renderTextFile(WEB_ROOT + "/jquery-1.7.2.min.js")
            val entity = HttpEntity(ContentType(MediaTypes.`application/javascript`), content)
            sender() ! HttpResponse(entity = entity)

          case HttpRequest(HttpMethods.GET, Uri.Path("/socket.io.js"), _, _, _) =>
            val content = renderTextFile(WEB_ROOT + "/socket.io.js")
            val entity = HttpEntity(ContentType(MediaTypes.`application/javascript`), content)
            sender() ! HttpResponse(entity = entity)

          case x: HttpRequest =>
            log.info("Got http req uri = {}", x.uri.path.toString.split("/").toList)

          case x: Frame =>
        }

        def renderTextFile(path: String) = {
          val source = scala.io.Source.fromFile(path)
          val lines = source.getLines mkString "\n"
          source.close()
          lines
        }
      }

      // --- json protocols for socketio messages:
      case class Msg(message: String)
      case class Now(time: String)
      object TheJsonProtocol extends DefaultJsonProtocol {
        implicit val msgFormat = jsonFormat1(Msg)
        implicit val nowFormat = jsonFormat1(Now)
      }
      import spray.json._
      import TheJsonProtocol._

      implicit val system = ActorSystem()
      val socketioExt = SocketIOExtension(system)
      val namespaceExt = NamespaceExtension(system)
      implicit val resolver = namespaceExt.resolver

      val observer = new Observer[OnEvent] {
        override def onNext(value: OnEvent) {
          value match {
            case event @ OnEvent("Hi!", args, context) =>
              println("observed: " + "Hi!" + ", " + args)
              if (event.packet.hasAckData) {
                event.ack("[]")
              }
              event.replyEvent("welcome", List(Msg("Greeting from spray-socketio")).toJson.toString)
              event.replyEvent("time", List(Now((new java.util.Date).toString)).toJson.toString)
              // batched packets
              event.reply(
                EventPacket(-1L, false, "testendpoint", "welcome", List(Msg("Batcher Greeting from spray-socketio")).toJson.toString),
                EventPacket(-1L, false, "testendpoint", "time", List(Now("Batched " + (new java.util.Date).toString)).toJson.toString))
            case OnEvent("time", args, context) =>
              println("observed: " + "time" + ", " + args)
            case _ =>
              println("observed: " + value)
          }
        }
      }

      val channel = Subject[OnData]()
      // there is no channel.ofType method for RxScala, why?
      channel.flatMap {
        case x: OnEvent => Observable.items(x)
        case _          => Observable.empty
      }.subscribe(observer)

      namespaceExt.startNamespace("testendpoint")
      namespaceExt.namespace("testendpoint") ! Namespace.Subscribe(channel)

      val server = system.actorOf(SocketIOServer.props(resolver), name = "socketio-server")

      IO(UHttp) ! Http.Bind(server, "localhost", 8080)

      readLine("Hit ENTER to exit ...\n")
      system.shutdown()
      system.awaitTermination()
    }


