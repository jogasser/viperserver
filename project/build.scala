import sbt._
import Keys._
import com.typesafe.sbt.packager.archetypes.JavaAppPackaging
import sbtassembly.AssemblyPlugin.autoImport._
import de.oakgrove.SbtBrand.{BrandKeys, BrandObject, Val, brandSettings}
import de.oakgrove.SbtHgId.{HgIdKeys, hgIdSettings}

object ViperServerBuild extends Build {

  /* Base settings */

  lazy val baseSettings = (
    hgIdSettings
      ++ brandSettings
      ++ Seq(
      organization := "viper",
      version := "1.1-SNAPSHOT",
      scalaVersion := "2.11.8",
      scalacOptions in Compile ++= Seq(
        "-deprecation",
        "-unchecked",
        "-feature"
        /*"-Xfatal-warnings"*/),
      unmanagedResourceDirectories in Compile := Seq(baseDirectory.value / "src/main/resources"),
      includeFilter in unmanagedResources := "jawr.properties",
      resolvers += "Sonatype OSS Snapshots" at "http://oss.sonatype.org/content/repositories/snapshots/",
      traceLevel := 10,
      maxErrors := 6))

  /* Projects */

  lazy val viper = {
    var p = Project(
      id = "viper",
      base = file("."),
      settings = (
        baseSettings
          ++ Seq(
          name := "ViperServer",
          mainClass in assembly := Some("viper.server.ViperServerRunner"),
          assemblyMergeStrategy in assembly := {
            case PathList("META-INF", xs @ _*) => MergeStrategy.discard
            case "reference.conf" => MergeStrategy.concat
            case x => MergeStrategy.first
          },
          jarName in assembly := "viper.jar",
          test in assembly := {},
          /* Skip tests before assembling fat jar. Assembling stops if tests fails. */
          // scalacOptions ++= Seq("-Xelide-below", "1000"),
          /* remove elidable method calls such as in SymbExLogger during compiling */
          fork := true,
          /* Fork Silicon when run and tested. Avoids problems with file
           * handlers on Windows 7 that remain open until Sbt is closed,
           * which makes it very annoying to work on test files.
           *
           * There have been reports about problems with forking. If you
           * experience strange problems, disable forking and try again.
           *
           * Malte 2013-11-18: Jenkins failed with
           * "OutOfMemoryError: unable to create new native thread".
           * Reducing the stack size from 256M to 128M seems to resolve
           * the problem and Silicon seems to be fine with less stack.
           * Not sure what to do if Silicon really required so much
           * stack at some point.
           */
          javaOptions in run ++= Seq("-Xss128M", "-Xmx1512M", "-Dfile.encoding=UTF-8"),
          javaOptions in Test ++= Seq("-Xss128M", "-Xmx1512M"),
          /* Options passed to JVMs forked by test-related Sbt command.
           * See http://www.scala-sbt.org/0.12.4/docs/Detailed-Topics/Forking.html
           * In contrast to what the documentation states, it seemed
           * that neither were the options passed to Sbt's JVM forwarded
           * to forked JVMs, nor did "javaOptions in (Test,run)"
           * work for me (Malte, using Sbt 0.12.4).
           * You can inspect the settings in effect using via
           * "show javaOptions" on the Sbt console.
           */

          libraryDependencies ++= externalDep,
          BrandKeys.dataPackage := "viper.server",
          BrandKeys.dataObject := "brandingData",
          BrandKeys.data += Val("buildDate", new java.text.SimpleDateFormat("yyyy/MM/dd HH:mm:ss").format(new java.util.Date)),
          BrandKeys.data <+= scalaVersion(Val("scalaVersion", _)),
          BrandKeys.data <+= sbtBinaryVersion(Val("sbtBinaryVersion", _)),
          BrandKeys.data <+= sbtVersion(Val("sbtVersion", _)),
          BrandKeys.data <+= name(Val("sbtProjectName", _)),
          BrandKeys.data <+= version(Val("sbtProjectVersion", _)),
          BrandKeys.data <++= HgIdKeys.projectId(idOrException => {
            val id =
              idOrException.fold(Predef.identity,
                _ => de.oakgrove.SbtHgId.Id("<unknown>", "<unknown>", "<unknown>", "<unknown>"))

            Seq(Val("hgid_version", id.version),
              Val("hgid_id", id.id),
              Val("hgid_branch", id.branch),
              Val("hgid_tags", id.tags))
          }),
          sourceGenerators in Compile <+= BrandKeys.generateDataFile)
          ++ addCommandAlias("tn", "test-only -- -n "))
    ).dependsOn(common)

    for (dep <- internalDep) {
      p = p.dependsOn(dep)
    }

    p.aggregate(common)
    p.enablePlugins(JavaAppPackaging)
  }


  lazy val common = Project(
    id = "common",
    base = file("common"),
    settings = (
      baseSettings
        ++ Seq(name := "ViperServer-Common",
        javacOptions ++= Seq("-source", "1.7", "-target", "1.7"),
        libraryDependencies += dependencies.commonsIO)))

  /* On the build-server, we cannot have all project in the same directory, and
   * thus we use the publish-local mechanism for dependencies.
   */

  def isBuildServer = sys.env.contains("BUILD_TAG") /* Should only be defined on the build server */

  def internalDep = if (isBuildServer) Nil else Seq(
    dependencies.silverSrc % "compile->compile;test->test",
    dependencies.carbonSrc % "compile->compile;test->test",
    dependencies.siliconSrc % "compile->compile;test->test")

  def externalDep = (
    Seq(dependencies.jgrapht, dependencies.commonsIO, dependencies.commonsPool, dependencies.scallop,
      dependencies.actors, dependencies.akka_testing, dependencies.akka_http_testing, dependencies.akka_json)
      ++ dependencies.logging
      ++ (if (isBuildServer) Seq(
      dependencies.silver % "compile->compile;test->test",
      dependencies.carbon % "compile->compile;test->test",
      dependencies.silicon % "compile->compile;test->test") else Nil))

  /* Dependencies */

  object dependencies {
    lazy val logging = Seq(
      "org.slf4j" % "slf4j-api" % "1.7.12",
      "ch.qos.logback" % "logback-classic" % "1.2.3")

    lazy val scallop = "org.rogach" %% "scallop" % "2.0.7"
    lazy val jgrapht = "org.jgrapht" % "jgrapht-core" % "0.9.1"

    lazy val commonsIO = "commons-io" % "commons-io" % "2.5"
    lazy val commonsPool = "org.apache.commons" % "commons-pool2" % "2.4.2"

    lazy val silver = "viper" %% "silver" % "0.1-SNAPSHOT"
    lazy val silverSrc = RootProject(new java.io.File("../silver"))
    lazy val silicon = "viper" %% "silicon" % "1.1-SNAPSHOT"
    lazy val siliconSrc = RootProject(new java.io.File("../silicon"))
    lazy val carbon = "viper" %% "carbon" % "1.0-SNAPSHOT"
    lazy val carbonSrc = RootProject(new java.io.File("../carbon"))

    lazy val actors = "com.typesafe.akka" %% "akka-actor" % "2.4.17"
    lazy val akka_testing = "com.typesafe.akka" %% "akka-testkit" % "2.4.17" % "test"
    lazy val akka_http_testing = "com.typesafe.akka" %% "akka-http-testkit" % "10.0.10"
    lazy val akka_json = "com.typesafe.akka" %% "akka-http-spray-json" % "10.0.7"
  }

}