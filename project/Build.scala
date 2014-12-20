import sbt._
import sbt.Keys._
import com.typesafe.sbt.SbtMultiJvm
import com.typesafe.sbt.SbtMultiJvm.MultiJvmKeys.MultiJvm
import com.typesafe.sbt.SbtScalariform
import com.typesafe.sbt.SbtScalariform.ScalariformKeys

object Build extends sbt.Build {

  lazy val root = Project("spray-socketio-root", file("."))
    .aggregate(examples, socketio)
    .settings(basicSettings: _*)
    .settings(noPublishing: _*)
    .settings(XitrumPackage.skip: _*)

 lazy val socketio = Project("spray-socketio", file("spray-socketio"))
    .settings(basicSettings: _*)
    .settings(formatSettings: _*)
    .settings(releaseSettings: _*)
    .settings(SbtMultiJvm.multiJvmSettings ++ multiJvmSettings: _*)
    .settings(libraryDependencies ++= Dependencies.all)
    .settings(unmanagedSourceDirectories in Test += baseDirectory.value / "multi-jvm/scala")
    .settings(XitrumPackage.skip: _*)
    .configs(MultiJvm)

  lazy val examples = Project("spray-socketio-examples", file("examples"))
    .aggregate(sprayBenchmark, sprayServer)
    .settings(XitrumPackage.skip: _*)
    .settings(exampleSettings: _*)

  lazy val sprayBenchmark = Project("spray-socketio-examples-benchmark", file("examples/socketio-benchmark"))
    .settings(exampleSettings: _*)
    .settings(libraryDependencies += Dependencies.akka_persistence_cassandra)
    .settings(formatSettings: _*)
    .settings(XitrumPackage.copy("bin", "conf", "logs"): _*)
    .dependsOn(socketio)

  lazy val sprayServer = Project("spray-socketio-examples-server", file("examples/socketio-server"))
    .settings(exampleSettings: _*)
    .settings(libraryDependencies += Dependencies.akka_persistence_cassandra)
    .settings(formatSettings: _*)
    .settings(XitrumPackage.skip: _*)
    .dependsOn(socketio)

  lazy val basicSettings = Seq(
      organization := "com.wandoulabs.akka",
      version := "0.2.0-SNAPSHOT",
      scalaVersion := "2.11.4",
      crossScalaVersions := Seq("2.10.4", "2.11.4"),
      scalacOptions ++= Seq("-unchecked", "-deprecation"),
      resolvers ++= Seq(
        "Sonatype OSS Releases" at "https://oss.sonatype.org/content/repositories/releases",
        "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots",
        "Typesafe repo" at "http://repo.typesafe.com/typesafe/releases/",
        "spray" at "http://repo.spray.io",
        "spray nightly" at "http://nightlies.spray.io/",
        "krasserm at bintray" at "http://dl.bintray.com/krasserm/maven")
      )

  lazy val exampleSettings = basicSettings ++ noPublishing

  lazy val releaseSettings = Seq(
      publishTo := {
        val nexus = "https://oss.sonatype.org/"
        if (version.value.trim.endsWith("SNAPSHOT"))
          Some("snapshots" at nexus + "content/repositories/snapshots")
        else
          Some("releases"  at nexus + "service/local/staging/deploy/maven2")
      },
      publishMavenStyle := true,
      publishArtifact in Test := false,
      pomIncludeRepository := { (repo: MavenRepository) => false },
      pomExtra := (
        <url>https://github.com/wandoulabs/spray-socketio</url>
          <licenses>
            <license>
              <name>The Apache Software License, Version 2.0</name>
              <url>http://www.apache.org/licenses/LICENSE-2.0.txt</url>
              <distribution>repo</distribution>
            </license>
          </licenses>
          <scm>
            <url>git@github.com:wandoulabs/spray-socketio.git</url>
            <connection>scm:git:git@github.com:wandoulabs/spray-socketio.git</connection>
          </scm>
          <developers>
            <developer>
              <id>dcaoyuan</id>
              <name>Caoyuan DENG</name>
              <email>dcaoyuan@gmail.com</email>
            </developer>
            <developer>
              <id>cowboy129</id>
              <name>Xingrun CHEN</name>
              <email>cowboy129@gmail.com</email>
            </developer>
          </developers>
        )
    )

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

  lazy val noPublishing = Seq(
    publish := (),
    publishLocal := (),
    // required until these tickets are closed https://github.com/sbt/sbt-pgp/issues/42,
    // https://github.com/sbt/sbt-pgp/issues/36
    publishTo := None
  )

  lazy val formatSettings = SbtScalariform.scalariformSettings ++ Seq(
    ScalariformKeys.preferences in Compile := formattingPreferences,
    ScalariformKeys.preferences in Test := formattingPreferences)

  import scalariform.formatter.preferences._
  def formattingPreferences =
    FormattingPreferences()
      .setPreference(RewriteArrowSymbols, false)
      .setPreference(AlignParameters, true)
      .setPreference(AlignSingleLineCaseStatements, true)
      .setPreference(DoubleIndentClassDeclaration, true)
      .setPreference(IndentSpaces, 2)

}

object Dependencies {
  val SPRAY_VERSION = "1.3.2-20140909"
  val AKKA_VERSION = "2.3.8"

  val spray_websocket = "com.wandoulabs.akka" %% "spray-websocket" % "0.1.3"
  val spray_can = "io.spray" %% "spray-can" % SPRAY_VERSION
  val spray_json = "io.spray" %% "spray-json" % "1.2.6" 
  val akka_actor = "com.typesafe.akka" %% "akka-actor" % AKKA_VERSION
  val akka_contrib = "com.typesafe.akka" %% "akka-contrib" % AKKA_VERSION
  val akka_stream = "com.typesafe.akka" %% "akka-stream-experimental" % "0.9"
  val parboiled = "org.parboiled" %% "parboiled-scala" % "1.1.6"
  val parboiled2 = "org.parboiled" %% "parboiled" % "2.0-M2" //changing ()
  val akka_testkit = "com.typesafe.akka" %% "akka-testkit" % AKKA_VERSION % "test"
  val akka_multinode_testkit = "com.typesafe.akka" %% "akka-multi-node-testkit" % AKKA_VERSION % "test"
  val scalatest = "org.scalatest" %% "scalatest" % "2.1.3" % "test"
  val apache_math = "org.apache.commons" % "commons-math3" % "3.2" // % "test"
  val caliper = "com.google.caliper" % "caliper" % "0.5-rc1" % "test"
  val akka_persistence_cassandra =  "com.github.krasserm" %% "akka-persistence-cassandra" % "0.3.3"
  
  val logback = "ch.qos.logback" % "logback-classic" % "1.0.13" //% "runtime"
  val akka_slf4j = "com.typesafe.akka" %% "akka-slf4j" % AKKA_VERSION //% "runtime"

  val all = Seq(spray_websocket, spray_can, spray_json, akka_actor, akka_contrib, akka_stream, parboiled, akka_testkit, akka_multinode_testkit, scalatest, apache_math, caliper, logback, akka_slf4j)
}
