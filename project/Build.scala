import sbt._
import sbt.Keys._

object Build extends sbt.Build {

  lazy val proj = Project(
    "spray-socketio",
    file("."),
    settings = commonSettings ++ Seq(
      libraryDependencies ++= Dependencies.all))

  def commonSettings = Defaults.defaultSettings ++
    Seq(
      organization := "com.wandoulabs",
      version := "0.1",
      scalaVersion := "2.10.3",
      scalacOptions ++= Seq("-unchecked", "-deprecation"),
      publishTo <<= isSnapshot { isSnapshot =>
        val id = if (isSnapshot) "snapshots" else "releases"
        val uri = "http://repo.scala-sbt.org/scalasbt/sbt-plugin-" + id
        Some(Resolver.url("sbt-plugin-" + id, url(uri))(Resolver.ivyStylePatterns))
      },
      publishMavenStyle := false,
      resolvers ++= Seq(
        "typesafe repo" at "http://repo.typesafe.com/typesafe/releases/",
        "spray" at "http://repo.spray.io",
        "spray nightly" at "http://nightlies.spray.io/",
        "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots"))
}

object Dependencies {
  val spray_websocket = "com.wandoulabs" % "spray-websocket_2.10" % "0.1"
  val spray_can = "io.spray" % "spray-can" % "1.3-RC1"
  val akka_actor = "com.typesafe.akka" %% "akka-actor" % "2.3.0-RC1"
  val parboiled = "org.parboiled" %% "parboiled-scala" % "1.1.5"
  val parboiled2 = "org.parboiled" %% "parboiled" % "2.0-SNAPSHOT" changing () // ApacheV2
  val akka_testkit = "com.typesafe.akka" %% "akka-testkit" % "2.3.0-RC1" % "test"
  val scalatest = "org.scalatest" %% "scalatest" % "2.0" % "test"

  val all = Seq(spray_websocket, spray_can, akka_actor, parboiled2, akka_testkit, scalatest)
}
