import SbtShared._

def akka(module: String) = "com.typesafe.akka" %% ("akka-" + module) % "2.5.26"

val akkaHttpVersion = "10.1.11"

val startAllCommands = List(
  "sbtRunner/reStart",
  "server/reStart",
  "client/fastOptJS::startWebpackDevServer",
)

def sbtJoinTask(commands: List[String]): String =
  commands.mkString(";", ";", "")

def startAll(commands: List[String], suffix: String = "") =
  addCommandAlias("startAll" + suffix, sbtJoinTask(commands))

lazy val ciAlias =
  addCommandAlias(
    "ci",
    sbtJoinTask(
      List(
        "test:compile",
        "instrumentation/test",
        "server/test",
        "storage/test"
        // https://github.com/scalacenter/scastie/issues/105
        // "e2e/test",

        // https://github.com/scalacenter/scastie/issues/363
        // "utils/test"
        // "balancer/test",
        // "sbtRunner/test"
      )
    )
  )

startAll(startAllCommands)

lazy val scastie = project
  .in(file("."))
  .aggregate(
    (api.projectRefs ++ runtimeScala.projectRefs ++ List(
      balancer,
      client,
      instrumentation,
      sbtRunner,
      sbtScastie,
      server,
      storage,
      utils,
    ).map(_.project)):_*
  )
  .settings(baseSettings)
  .settings(ciAlias)
  .settings(Deployment.settings(server, sbtRunner))

lazy val testSettings =
  Seq(
    libraryDependencies += "org.scalatest" %% "scalatest" % "3.1.0" % Test
  )

lazy val loggingAndTest =
  Seq(
    libraryDependencies ++= Seq(
      "ch.qos.logback" % "logback-classic" % "1.1.7",
      "com.typesafe.scala-logging" %% "scala-logging" % "3.9.2",
      "com.getsentry.raven" % "raven-logback" % "8.0.3"
    )
  ) ++ testSettings

lazy val utils = project
  .in(file("utils"))
  .settings(baseNoCrossSettings)
  .settings(loggingAndTest)
  .settings(
    resolvers += Resolver.typesafeRepo("releases"),
    libraryDependencies ++= Seq(
      akka("protobuf"),
      akka("stream"),
      akka("actor"),
      akka("remote"),
      akka("slf4j"),
      akka("testkit") % Test
    )
  )
  .dependsOn(api.jvm(ScalaVersions.jvm))

lazy val runnerRuntimeDependencies = (api.projectRefs ++ runtimeScala.projectRefs ++ Seq(
    sbtScastie.project
  )).map(publishLocal in _)

lazy val runnerRuntimeDependenciesInTest = Seq(
  test in assembly := {},
  test in Test := (test in Test).dependsOn(runnerRuntimeDependencies: _*).value,
  testOnly in Test := (testOnly in Test).dependsOn(runnerRuntimeDependencies: _*).evaluated,
  testQuick in Test := (testQuick in Test).dependsOn(runnerRuntimeDependencies: _*).evaluated
)

lazy val smallRunnerRuntimeDependenciesInTest = {
  lazy val smallRunnerRuntimeDependencies = Seq(
    api.jvm(ScalaVersions.jvm),
    runtimeScala.jvm(ScalaVersions.jvm),
    sbtScastie
  ).map(publishLocal in _)
  Seq(
    test in assembly := {},
    test in Test := (test in Test).dependsOn(smallRunnerRuntimeDependencies: _*).value,
    testOnly in Test := (testOnly in Test).dependsOn(smallRunnerRuntimeDependencies: _*).evaluated,
    testQuick in Test := (testQuick in Test).dependsOn(smallRunnerRuntimeDependencies: _*).evaluated
  )
}

lazy val dockerOrg = "scalacenter"

lazy val sbtRunner = project
  .in(file("sbt-runner"))
  .settings(baseNoCrossSettings)
  .settings(loggingAndTest)
  .settings(runnerRuntimeDependenciesInTest)
  .settings(
    javaOptions in reStart += "-Xmx256m",
    parallelExecution in Test := false,
    reStart := reStart.dependsOn(runnerRuntimeDependencies: _*).evaluated,
    resolvers += Resolver.sonatypeRepo("public"),
    libraryDependencies ++= Seq(
      akka("actor"),
      akka("testkit") % Test,
      akka("remote"),
      akka("slf4j"),
      "org.scalameta" %% "scalafmt-core" % "2.3.2"
    ),
    imageNames in docker := Seq(
      ImageName(
        namespace = Some(dockerOrg),
        repository = "scastie-sbt-runner",
        tag = Some(gitHashNow)
      )
    ),
    dockerfile in docker := Def
      .task {
        DockerHelper(
          baseDirectory = (baseDirectory in ThisBuild).value.toPath,
          sbtTargetDir = target.value.toPath,
          ivyHome = ivyPaths.value.ivyHome.get.toPath,
          organization = organization.value,
          artifact = assembly.value.toPath,
          sbtScastie = (moduleName in sbtScastie).value
        )
      }
      .dependsOn(runnerRuntimeDependencies: _*)
      .value,
    assemblyMergeStrategy in assembly := {
      case PathList("META-INF", xs @ _*) => MergeStrategy.discard
      case in @ PathList("reference.conf", xs @ _*) => {
        val old = (assemblyMergeStrategy in assembly).value
        old(in)
      }
      case x => MergeStrategy.first
    }
  )
  .dependsOn(api.jvm(ScalaVersions.jvm), instrumentation, utils)
  .enablePlugins(sbtdocker.DockerPlugin, BuildInfoPlugin)

lazy val server = project
  .settings(baseNoCrossSettings)
  .settings(loggingAndTest)
  .settings(ScalaJsHelper.packageScalaJS(client))
  .settings(
    javaOptions in reStart += "-Xmx512m",
    maintainer := "scalacenter",
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-http" % akkaHttpVersion,
      "com.softwaremill.akka-http-session" %% "core" % "0.5.10",
      "ch.megard" %% "akka-http-cors" % "0.4.2",
      akka("remote"),
      akka("slf4j"),
      akka("testkit") % Test,
      "com.typesafe.akka" %% "akka-http-testkit" % akkaHttpVersion % Test
    )
  )
  .enablePlugins(JavaServerAppPackaging)
  .dependsOn(api.jvm(ScalaVersions.jvm), utils, balancer)

lazy val balancer = project
  .settings(baseNoCrossSettings)
  .settings(loggingAndTest)
  .settings(smallRunnerRuntimeDependenciesInTest)
  .settings(
    libraryDependencies += akka("testkit") % Test
  )
  .dependsOn(api.jvm(ScalaVersions.jvm), utils, storage, sbtRunner % Test)

lazy val storage = project
  .settings(baseNoCrossSettings)
  .settings(loggingAndTest)
  .settings(
    libraryDependencies ++= Seq(
      "net.lingala.zip4j" % "zip4j" % "1.3.1",
      "org.reactivemongo" %% "reactivemongo" % "0.20.1",
      "org.reactivemongo" %% "reactivemongo-play-json" % "0.20.1-play28",
      "org.reactivemongo" %% "reactivemongo-play-json-compat" % "0.20.1-play28",
    )
  )
  .dependsOn(api.jvm(ScalaVersions.jvm), utils, instrumentation)

val webpackDir = Def.setting {
  (baseDirectory in ThisProject).value / "webpack"
}

val webpackDevConf = Def.setting {
  Some(webpackDir.value / "webpack-dev.config.js")
}

val webpackProdConf = Def.setting {
  Some(webpackDir.value / "webpack-prod.config.js")
}

lazy val client = project
  .settings(baseNoCrossSettings)
  .settings(baseJsSettings)
  .settings(
    version in webpack := "3.5.5",
    version in startWebpackDevServer := "2.7.1",
    webpackConfigFile in fastOptJS := webpackDevConf.value,
    webpackConfigFile in fullOptJS := webpackProdConf.value,
    webpackMonitoredDirectories += (resourceDirectory in Compile).value,
    webpackResources := webpackDir.value * "*.js",
    includeFilter in webpackMonitoredFiles := "*",
    useYarn := true,
    webpackBundlingMode in fastOptJS := BundlingMode.LibraryOnly(),
    webpackBundlingMode in fullOptJS := BundlingMode.Application,
    test := {},
    Test / loadedTestFrameworks := Map(),
    npmDependencies in Compile ++= Seq(
      "codemirror" -> "5.50.0",
      "firacode" -> "1.205.0",
      "font-awesome" -> "4.7.0",
      "raven-js" -> "3.11.0",
      "react" -> "16.7.0",
      "react-dom" -> "16.7.0",
      "typeface-roboto-slab" -> "0.0.35"
    ),
    npmDevDependencies in Compile ++= Seq(
      "compression-webpack-plugin" -> "1.0.0",
      "clean-webpack-plugin" -> "0.1.16",
      "css-loader" -> "0.28.5",
      "extract-text-webpack-plugin" -> "3.0.0",
      "file-loader" -> "0.11.2",
      "html-webpack-plugin" -> "2.30.1",
      "node-sass" -> "4.5.3",
      "resolve-url-loader" -> "2.1.0",
      "sass-loader" -> "6.0.6",
      "style-loader" -> "0.18.2",
      "uglifyjs-webpack-plugin" -> "0.4.6",
      "webpack-merge" -> "4.1.0"
    ),
    libraryDependencies ++= Seq(
      "com.github.japgolly.scalajs-react" %%% "extra" % "1.5.0",
    )
  )
  .enablePlugins(ScalaJSPlugin, ScalaJSBundlerPlugin)
  .dependsOn(api.js(ScalaVersions.js))

lazy val instrumentation = project
  .settings(baseNoCrossSettings)
  .settings(loggingAndTest)
  .settings(
    libraryDependencies ++= Seq(
      "org.scalameta" %% "scalameta" % "4.3.0",
      "com.googlecode.java-diff-utils" % "diffutils" % "1.3.0" % Test
    )
  )
  .dependsOn(api.jvm(ScalaVersions.jvm), utils)

lazy val api = apiProject
lazy val runtimeScala = runtimeScalaProject

lazy val sbtScastie = project
  .in(file("sbt-scastie"))
  .settings(orgSettings)
  .settings(
    moduleName := "sbt-scastie",
    sbtPlugin := true
  )
  .settings(version := versionRuntime)
  .dependsOn(api.jvm(ScalaVersions.sbt))

