import sbt._
import sbt.Keys._
import com.typesafe.sbt.SbtMultiJvm
import com.typesafe.sbt.SbtMultiJvm.MultiJvmKeys.MultiJvm

object Build extends sbt.Build {

  lazy val proj = Project(
    "spray-socketio",
    file("."),
    settings = commonSettings ++ SbtMultiJvm.multiJvmSettings ++ Seq(
      libraryDependencies ++= Dependencies.all,
      unmanagedSourceDirectories in Test += baseDirectory.value / "multi-jvm/scala",
      distTask) ++ multiJvmSettings) configs (MultiJvm)

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
        "Sonatype OSS Releases" at "https://oss.sonatype.org/content/repositories/releases",
        "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots",
        "Typesafe repo" at "http://repo.typesafe.com/typesafe/releases/",
        "spray" at "http://repo.spray.io",
        "spray nightly" at "http://nightlies.spray.io/"))

  def multiJvmSettings = Seq(
    // make sure that MultiJvm test are compiled by the default test compilation
    compile in MultiJvm <<= (compile in MultiJvm) triggeredBy (compile in Test),
    // disable parallel tests
    parallelExecution in Test := false,
    // make sure that MultiJvm tests are executed by the default test target,
    // and combine the results from ordinary test and multi-jvm tests
    executeTests in Test <<= (executeTests in Test, executeTests in MultiJvm) map {
      case (testResults, multiNodeResults) =>
        val overall =
          if (testResults.overall.id < multiNodeResults.overall.id)
            multiNodeResults.overall
          else
            testResults.overall
        Tests.Output(overall,
          testResults.events ++ multiNodeResults.events,
          testResults.summaries ++ multiNodeResults.summaries)
    })

  lazy val distDependencies = TaskKey[Unit]("dist")
  def distTask = distDependencies <<= (packageBin in Compile, update, crossTarget, scalaVersion) map { (bin, updateReport, out, scalaVer) =>
    (bin :: updateReport.allFiles.toList) foreach { srcPath =>
      val destPath = out / "dist/libs" / srcPath.getName
      IO.copyFile(srcPath, destPath, preserveLastModified = true)
    }
  }
}

object Dependencies {
  val SPRAY_VERSION = "1.3.0"
  val AKKA_VERSION = "2.3.0"

  val spray_websocket = "com.wandoulabs" % "spray-websocket_2.10" % "0.1"
  val spray_can = "io.spray" % "spray-can" % SPRAY_VERSION
  val akka_actor = "com.typesafe.akka" %% "akka-actor" % AKKA_VERSION
  val akka_contrib = "com.typesafe.akka" %% "akka-contrib" % AKKA_VERSION
  val parboiled = "org.parboiled" %% "parboiled-scala" % "1.1.5"
  val parboiled2 = "org.parboiled" %% "parboiled" % "2.0-M2" //changing ()
  val akka_testkit = "com.typesafe.akka" %% "akka-testkit" % AKKA_VERSION % "test"
  val akka_multinode_testkit = "com.typesafe.akka" %% "akka-multi-node-testkit" % AKKA_VERSION % "test"
  val scalatest = "org.scalatest" %% "scalatest" % "2.0" % "test"
  val rxscala = "com.netflix.rxjava" % "rxjava-scala" % "0.17.1"
  val apache_math = "org.apache.commons" % "commons-math3" % "3.2" // % "test"

  val all = Seq(spray_websocket, spray_can, akka_actor, akka_contrib, parboiled2, rxscala, akka_testkit, akka_multinode_testkit, scalatest, apache_math)
}
