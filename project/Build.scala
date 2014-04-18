import sbt._
import sbt.Keys._
import com.typesafe.sbt.SbtMultiJvm
import com.typesafe.sbt.SbtMultiJvm.MultiJvmKeys.MultiJvm
import com.typesafe.sbt.SbtScalariform
import com.typesafe.sbt.SbtScalariform.ScalariformKeys

object Build extends sbt.Build {

  lazy val proj = Project(
    "spray-socketio",
    file("."),
    settings = commonSettings ++ SbtMultiJvm.multiJvmSettings ++ Seq(
      libraryDependencies ++= Dependencies.all,
      unmanagedSourceDirectories in Test += baseDirectory.value / "multi-jvm/scala",
      distTask) ++ multiJvmSettings) configs (MultiJvm)

  def commonSettings = Defaults.defaultSettings ++
    formatSettings ++
    Seq(
      organization := "com.wandoulabs.akka",
      version := "0.1.1-SNAPSHOT",
      scalaVersion := "2.10.3",
      scalacOptions ++= Seq("-unchecked", "-deprecation"),
      resolvers ++= Seq(
        "Sonatype OSS Releases" at "https://oss.sonatype.org/content/repositories/releases",
        "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots",
        "Typesafe repo" at "http://repo.typesafe.com/typesafe/releases/",
        "spray" at "http://repo.spray.io",
        "spray nightly" at "http://nightlies.spray.io/"),
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

  lazy val distDependencies = TaskKey[Unit]("dist")
  def distTask = distDependencies <<= (packageBin in Compile, update, crossTarget, scalaVersion) map { (bin, updateReport, out, scalaVer) =>
    (bin :: updateReport.allFiles.toList) foreach { srcPath =>
      val destPath = out / "dist/libs" / srcPath.getName
      IO.copyFile(srcPath, destPath, preserveLastModified = true)
    }
  }

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
  val SPRAY_VERSION = "1.3.0"
  val AKKA_VERSION = "2.3.2"

  val spray_websocket = "com.wandoulabs.akka" %% "spray-websocket" % "0.1.1-SNAPSHOT"
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
  val caliper = "com.google.caliper" % "caliper" % "0.5-rc1" % "test"

  val all = Seq(spray_websocket, spray_can, akka_actor, akka_contrib, parboiled2, rxscala, akka_testkit, akka_multinode_testkit, scalatest, apache_math, caliper)
}
