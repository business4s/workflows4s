import org.typelevel.scalacoptions.ScalacOptions

lazy val `workflows4s` = (project in file("."))
  .settings(commonSettings)
  .aggregate(
    `workflows4s-core`,
    `workflows4s-bpmn`,
    `workflows4s-pekko`,
    `workflows4s-example`,
    `workflows4s-doobie`,
    `workflows4s-filesystem`,
    `workflows4s-quartz`,
    `workflows4s-web-ui`,
    `workflows4s-web-ui-bundle`,
    `workflows4s-web-api-shared`.js,
    `workflows4s-web-api-shared`.jvm,
    `workflows4s-web-api-server`,
  )

lazy val `workflows4s-core` = (project in file("workflows4s-core"))
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "org.typelevel"              %% "cats-effect"   % "3.6.2",
      "co.fs2"                     %% "fs2-core"      % "3.12.0",
      "com.typesafe.scala-logging" %% "scala-logging" % "3.9.5",
      "io.circe"                   %% "circe-core"    % circeVersion, // for model serialization
      "io.circe"                   %% "circe-generic" % circeVersion, // for model serialization
      "com.lihaoyi"                %% "sourcecode"    % "0.4.2",      // for auto naming
    ),
    Test / parallelExecution := false,
  )

lazy val `workflows4s-bpmn` = (project in file("workflows4s-bpmn"))
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "org.camunda.bpm.model" % "camunda-bpmn-model" % "7.23.0",
    ),
  )
  .dependsOn(`workflows4s-core`)

lazy val `workflows4s-pekko` = (project in file("workflows4s-pekko"))
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "org.apache.pekko" %% "pekko-persistence-typed"      % pekkoVersion,
      "org.apache.pekko" %% "pekko-cluster-typed"          % pekkoVersion,
      "org.apache.pekko" %% "pekko-cluster-sharding-typed" % pekkoVersion,
      "org.apache.pekko" %% "pekko-persistence-testkit"    % pekkoVersion    % Test,
      "org.apache.pekko" %% "pekko-persistence-jdbc"       % "1.1.0"         % Test,
      "com.h2database"    % "h2"                           % "2.3.232"       % Test,
      "io.r2dbc"          % "r2dbc-h2"                     % "1.0.0.RELEASE" % Test,
    ),
  )
  .dependsOn(`workflows4s-core` % "compile->compile;test->test")

lazy val `workflows4s-doobie` = (project in file("workflows4s-doobie"))
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "org.tpolecat"  %% "doobie-core"                     % "1.0.0-RC10",
      "com.dimafeng"  %% "testcontainers-scala-scalatest"  % testcontainersScalaVersion % Test,
      "com.dimafeng"  %% "testcontainers-scala-postgresql" % testcontainersScalaVersion % Test,
      "org.postgresql" % "postgresql"                      % "42.7.7"                   % Test,
      "org.xerial"     % "sqlite-jdbc"                     % "3.50.3.0"                 % Test,
    ),
  )
  .dependsOn(`workflows4s-core` % "compile->compile;test->test")

lazy val `workflows4s-filesystem` = (project in file("workflows4s-filesystem"))
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "co.fs2" %% "fs2-io" % "3.12.0",
    ),
  )
  .dependsOn(`workflows4s-core` % "compile->compile;test->test")

lazy val `workflows4s-quartz` = (project in file("workflows4s-quartz"))
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "org.quartz-scheduler" % "quartz" % "2.5.0",
    ),
  )
  .dependsOn(`workflows4s-core` % "compile->compile;test->test")

lazy val `workflows4s-web-api-shared` = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Pure)
  .in(file("workflows4s-web-api-shared"))
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "com.softwaremill.sttp.tapir" %%% "tapir-core"       % tapirVersion,
      "com.softwaremill.sttp.tapir" %%% "tapir-json-circe" % tapirVersion,
      "io.circe"                    %%% "circe-core"       % circeVersion,
      "io.circe"                    %%% "circe-generic"    % circeVersion,
    ),
    publish / skip := true,
  )

lazy val `workflows4s-web-api-server` = (project in file("workflows4s-web-api-server"))
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "com.softwaremill.sttp.tapir" %% "tapir-http4s-server" % tapirVersion,
      "com.softwaremill.sttp.tapir" %% "tapir-json-circe"    % tapirVersion,
      "io.circe"                    %% "circe-generic"       % circeVersion,
      "io.circe"                    %% "circe-parser"        % circeVersion,
    ),
  )
  .dependsOn(
    `workflows4s-core`,
    `workflows4s-web-api-shared`.jvm,
  )

lazy val `workflows4s-web-ui` = (project in file("workflows4s-web-ui"))
  .enablePlugins(ScalaJSPlugin)
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "io.indigoengine"               %%% "tyrian-io"          % "0.14.0",
      "io.circe"                      %%% "circe-core"         % circeVersion,
      "io.circe"                      %%% "circe-generic"      % circeVersion,
      "io.circe"                      %%% "circe-parser"       % circeVersion,
      "com.softwaremill.sttp.tapir"   %%% "tapir-sttp-client4" % "1.11.40",
      "com.softwaremill.sttp.client4" %%% "cats"               % "4.0.0-M16",
    ),
    scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.ESModule) },
    publish / skip := true,
  )
  .dependsOn(`workflows4s-core`, `workflows4s-web-api-shared`.js)

lazy val `workflows4s-web-ui-bundle` = (project in file("workflows4s-web-ui-bundle"))
  .settings(commonSettings)
  .settings(
    name                := "workflows4s-web-ui-bundle",
    libraryDependencies ++= Seq(
      "com.softwaremill.sttp.tapir" %% "tapir-files" % tapirVersion,
    ),
    (Compile / compile) := ((Compile / compile) dependsOn (`workflows4s-web-ui` / Compile / fullLinkJS)).value,
    Compile / resourceGenerators += Def.taskDyn {
      val log      = streams.value.log
      val cacheDir = streams.value.cacheDirectory / "webui-bundleit-cache"

      val jsFileTask = (`workflows4s-web-ui` / Compile / fullLinkJSOutput).map(_ / "main.js")

      jsFileTask.map { jsFile =>
        val cached = FileFunction.cached(cacheDir, FilesInfo.hash) { _ =>
          log.info("Bundling webui due to source change or missing output.")
          BundleIt.bundle(
            from = (`workflows4s-web-ui` / baseDirectory).value,
            to = (Compile / resourceManaged).value,
          )
        }
        cached(Set(jsFile)).toSeq
      }
    },
  )

lazy val `workflows4s-example` = (project in file("workflows4s-example"))
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "org.http4s"           %% "http4s-ember-server"             % "0.23.30",
      "org.http4s"           %% "http4s-dsl"                      % "0.23.30",
      "org.apache.pekko"     %% "pekko-http"                      % pekkoHttpVersion, // for interacting with the app
      "org.apache.pekko"     %% "pekko-cluster-sharding-typed"    % pekkoVersion, // for realistic example and spawning actors
      "org.apache.pekko"     %% "pekko-persistence-jdbc"          % "1.1.1", // published locally until the release is there
      "org.apache.pekko"     %% "pekko-serialization-jackson"     % "1.1.5",
      "com.h2database"        % "h2"                              % "2.3.232",
      "io.r2dbc"              % "r2dbc-h2"                        % "1.0.0.RELEASE",
      "com.github.pjfanning" %% "pekko-http-circe"                % "3.2.1",
      "ch.qos.logback"        % "logback-classic"                 % "1.5.18",
      "org.scalamock"        %% "scalamock"                       % "7.4.0"                    % Test,
      "org.apache.pekko"     %% "pekko-actor-testkit-typed"       % pekkoVersion               % Test,
      "com.dimafeng"         %% "testcontainers-scala-scalatest"  % testcontainersScalaVersion % Test,
      "com.dimafeng"         %% "testcontainers-scala-postgresql" % testcontainersScalaVersion % Test,
      "org.postgresql"        % "postgresql"                      % "42.7.7"                   % Test,
      "org.xerial"            % "sqlite-jdbc"                     % "3.50.3.0"                 % Test,
    ),
    Test / parallelExecution := false, // otherwise akka clusters clash
    publish / skip           := true,
  )
  .dependsOn(
    `workflows4s-core`   % "compile->compile;test->test",
    `workflows4s-bpmn`,
    `workflows4s-pekko`  % "compile->compile;test->test",
    `workflows4s-doobie` % "compile->compile;test->test",
    `workflows4s-filesystem`,
    `workflows4s-quartz`,
    `workflows4s-web-api-server`,
    `workflows4s-web-ui-bundle`,
    `workflows4s-web-ui`,
  )
  .enablePlugins(JavaAppPackaging)
  .enablePlugins(DockerPlugin)
  .settings(
    Compile / discoveredMainClasses := Seq("workflows4s.example.api.ServerWithUI"),
    dockerExposedPorts              := Seq(8080),
    dockerBaseImage                 := "eclipse-temurin:21-jdk",
    dockerUpdateLatest              := true,
    dockerBuildOptions ++= Seq("--platform=linux/amd64"),
  )
  .settings(Revolver.enableDebugging(port = 5050))

lazy val commonSettings = Seq(
  scalaVersion      := "3.7.1",
  scalacOptions ++= Seq("-no-indent", "-Xmax-inlines", "64", "-explain-cyclic", "-Ydebug-cyclic"),
  libraryDependencies ++= Seq(
    "org.scalatest" %% "scalatest"       % "3.2.19" % Test,
    "ch.qos.logback" % "logback-classic" % "1.5.18" % Test,
  ),
  // scalafix settings
  semanticdbEnabled := true, // enable SemanticDB
  organization      := "org.business4s",
  homepage          := Some(url("https://business4s.github.io/workflows4s/")),
  licenses          := List(License.MIT),
  developers        := List(
    Developer(
      "Krever",
      "Voytek Pituła",
      "w.pitula@gmail.com",
      url("https://v.pitula.me"),
    ),
  ),
  versionScheme     := Some("semver-spec"),
  Test / tpolecatExcludeOptions += ScalacOptions.warnNonUnitStatement,
)

lazy val pekkoVersion               = "1.1.5"
lazy val pekkoHttpVersion           = "1.2.0"
lazy val testcontainersScalaVersion = "0.43.0"
lazy val tapirVersion               = "1.11.40"
lazy val circeVersion               = "0.14.14"

addCommandAlias("prePR", List("compile", "Test / compile", "test", "scalafmtCheckAll").mkString(";", ";", ""))

lazy val stableVersion = taskKey[String]("stableVersion")
stableVersion := {
  if (isVersionStable.value && !isSnapshot.value) version.value
  else previousStableVersion.value.getOrElse("unreleased")
}

ThisBuild / publishTo := {
  val centralSnapshots = "https://central.sonatype.com/repository/maven-snapshots/"
  if (isSnapshot.value) Some("central-snapshots" at centralSnapshots)
  else localStaging.value
}

Global / onChangedBuildSource := ReloadOnSourceChanges

// required for docker tags
ThisBuild / dynverSeparator := "-"
