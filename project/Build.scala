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
    .settings(Formatting.settings: _*)
    .settings(Formatting.buildFileSettings: _*)
    .settings(noPublishing: _*)

  lazy val socketio = Project("spray-socketio", file("spray-socketio"))
    .settings(basicSettings: _*)
    .settings(Formatting.settings: _*)
    .settings(releaseSettings: _*)
    .settings(libraryDependencies ++= Dependencies.all)
    .settings(SbtMultiJvm.multiJvmSettings ++ multiJvmSettings: _*)
    .settings(unmanagedSourceDirectories in Test += baseDirectory.value / "multi-jvm/scala")
    .configs(MultiJvm)

  lazy val examples = Project("spray-socketio-examples", file("examples"))
    .aggregate(sprayBenchmark, sprayServer)
    .settings(exampleSettings: _*)

  lazy val sprayBenchmark = Project("spray-socketio-examples-benchmark", file("examples/socketio-benchmark"))
    .settings(exampleSettings: _*)
    .settings(libraryDependencies += Dependencies.akka_persistence_cassandra)
    .settings(Formatting.settings: _*)
    .settings(Packaging.bench_settings: _*)
    .dependsOn(socketio)

  lazy val sprayServer = Project("spray-socketio-examples-server", file("examples/socketio-server"))
    .settings(exampleSettings: _*)
    .settings(libraryDependencies += Dependencies.akka_persistence_cassandra)
    .settings(Formatting.settings: _*)
    .dependsOn(socketio)

  lazy val basicSettings = Seq(
    organization := "com.wandoulabs.akka",
    version := "0.2.0-SNAPSHOT",
    scalaVersion := "2.11.5",
    // no more scala-2.10.x @see https://github.com/milessabin/shapeless/issues/63
    //crossScalaVersions := Seq("2.10.4", "2.11.5"),
    scalacOptions ++= Seq("-unchecked", "-deprecation"),
    resolvers ++= Seq(
      "Sonatype OSS Releases" at "https://oss.sonatype.org/content/repositories/releases",
      "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots",
      "Typesafe repo" at "http://repo.typesafe.com/typesafe/releases/",
      "spray" at "http://repo.spray.io",
      "spray nightly" at "http://nightlies.spray.io/",
      "krasserm at bintray" at "http://dl.bintray.com/krasserm/maven"))

  lazy val exampleSettings = basicSettings ++ noPublishing

  lazy val releaseSettings = Seq(
    publishTo := {
      val nexus = "https://oss.sonatype.org/"
      if (version.value.trim.endsWith("SNAPSHOT"))
        Some("snapshots" at nexus + "content/repositories/snapshots")
      else
        Some("releases" at nexus + "service/local/staging/deploy/maven2")
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
      </developers>))

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
    publishTo := None)
}

object Dependencies {
  val SPRAY_VERSION = "1.3.2"
  val AKKA_VERSION = "2.3.9"

  val spray_websocket = "com.wandoulabs.akka" %% "spray-websocket" % "0.1.5-SNAPSHOT"
  val spray_can = "io.spray" %% "spray-can" % SPRAY_VERSION
  val spray_json = "io.spray" %% "spray-json" % "1.3.1"
  val akka_actor = "com.typesafe.akka" %% "akka-actor" % AKKA_VERSION
  val akka_contrib = "com.typesafe.akka" %% "akka-contrib" % AKKA_VERSION
  val akka_stream = "com.typesafe.akka" %% "akka-stream-experimental" % "1.0-M3"
  val parboiled = "org.parboiled" %% "parboiled" % "2.0.1"
  val akka_testkit = "com.typesafe.akka" %% "akka-testkit" % AKKA_VERSION % "test"
  val akka_multinode_testkit = "com.typesafe.akka" %% "akka-multi-node-testkit" % AKKA_VERSION % "test"
  val scalatest = "org.scalatest" %% "scalatest" % "2.1.3" % "test"
  val apache_math = "org.apache.commons" % "commons-math3" % "3.2" // % "test"
  val caliper = "com.google.caliper" % "caliper" % "0.5-rc1" % "test"
  val akka_persistence_cassandra = "com.github.krasserm" %% "akka-persistence-cassandra" % "0.3.6"

  val logback = "ch.qos.logback" % "logback-classic" % "1.0.13" //% "runtime"
  val akka_slf4j = "com.typesafe.akka" %% "akka-slf4j" % AKKA_VERSION //% "runtime"

  val all = Seq(spray_websocket, spray_can, spray_json, akka_actor, akka_contrib, akka_stream, parboiled, akka_testkit, akka_multinode_testkit, scalatest, apache_math, caliper, logback, akka_slf4j)
}

object Formatting {
  import com.typesafe.sbt.SbtScalariform
  import com.typesafe.sbt.SbtScalariform.ScalariformKeys
  import ScalariformKeys._

  val BuildConfig = config("build") extend Compile
  val BuildSbtConfig = config("buildsbt") extend Compile

  // invoke: build:scalariformFormat
  val buildFileSettings: Seq[Setting[_]] = SbtScalariform.noConfigScalariformSettings ++
    inConfig(BuildConfig)(SbtScalariform.configScalariformSettings) ++
    inConfig(BuildSbtConfig)(SbtScalariform.configScalariformSettings) ++ Seq(
      scalaSource in BuildConfig := baseDirectory.value / "project",
      scalaSource in BuildSbtConfig := baseDirectory.value,
      includeFilter in (BuildConfig, format) := ("*.scala": FileFilter),
      includeFilter in (BuildSbtConfig, format) := ("*.sbt": FileFilter),
      format in BuildConfig := {
        val x = (format in BuildSbtConfig).value
        (format in BuildConfig).value
      },
      ScalariformKeys.preferences in BuildConfig := formattingPreferences,
      ScalariformKeys.preferences in BuildSbtConfig := formattingPreferences)

  val settings = SbtScalariform.scalariformSettings ++ Seq(
    ScalariformKeys.preferences in Compile := formattingPreferences,
    ScalariformKeys.preferences in Test := formattingPreferences)

  val formattingPreferences = {
    import scalariform.formatter.preferences._
    FormattingPreferences()
      .setPreference(RewriteArrowSymbols, false)
      .setPreference(AlignParameters, true)
      .setPreference(AlignSingleLineCaseStatements, true)
      .setPreference(DoubleIndentClassDeclaration, true)
      .setPreference(IndentSpaces, 2)
  }
}

object Packaging {
  import com.typesafe.sbt.SbtNativePackager._
  import com.typesafe.sbt.packager.Keys._
  import com.typesafe.sbt.packager.archetypes._

  val bench_settings = packagerSettings ++ deploymentSettings ++
    packageArchetype.java_application ++ Seq(
      mainClass in Compile := Some("spray.contrib.socketio.examples.benchmark.SocketIOTestClusterServer"),
      name := "bench_cluster",
      NativePackagerKeys.packageName := "bench_cluster",
      bashScriptConfigLocation := Some("${app_home}/../conf/jvmopts"),
      bashScriptExtraDefines += """addJava "-Dconfig.file=${app_home}/../conf/cluster.conf"""")
}

