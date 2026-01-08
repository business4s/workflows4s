import org.typelevel.scalacoptions.ScalacOptions

lazy val `workflows4s` = (project in file("."))
  .settings(commonSettings)
  .aggregate(
    `workflows4s-core`,
    `workflows4s-tck`,
    `workflows4s-cats`,
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
      "org.typelevel"              %% "cats-core"       % "2.13.0",
      "co.fs2"                     %% "fs2-core"        % "3.12.2",
      "com.typesafe.scala-logging" %% "scala-logging"   % "3.9.6",
      "io.circe"                   %% "circe-core"      % circeVersion, // for model serialization
      "io.circe"                   %% "circe-generic"   % circeVersion, // for model serialization
      "com.lihaoyi"                %% "sourcecode"      % "0.4.4", // for auto naming
      "ch.qos.logback"              % "logback-classic" % "1.5.18" % Test,
    ),
    Test / parallelExecution := false,
  )

lazy val `workflows4s-tck` = (project in file("workflows4s-tck"))
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "io.circe"                   %% "circe-core"      % circeVersion,
      "com.softwaremill.sttp.tapir" %% "tapir-core"     % tapirVersion,
      "org.scalatest"              %% "scalatest"       % "3.2.19"         % Test,
      "com.typesafe.scala-logging" %% "scala-logging"   % "3.9.6"          % Test,
      "ch.qos.logback"              % "logback-classic" % "1.5.18"         % Test,
    ),
    publish / skip := true,
  )
  .dependsOn(`workflows4s-core` % "compile->compile;test->test")

lazy val `workflows4s-cats` = (project in file("workflows4s-cats"))
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % "3.6.3",
    ),
  )
  .dependsOn(`workflows4s-core` % "compile->compile;test->test")

lazy val `workflows4s-bpmn` = (project in file("workflows4s-bpmn"))
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "org.camunda.bpm.model" % "camunda-bpmn-model" % "7.24.0",
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
      "org.apache.pekko" %% "pekko-persistence-jdbc"       % "1.1.1"         % Test,
      "com.h2database"    % "h2"                           % "2.4.240"       % Test,
      "io.r2dbc"          % "r2dbc-h2"                     % "1.1.0.RELEASE" % Test,
      "io.altoo"         %% "pekko-kryo-serialization"     % "1.3.2",
    ),
  )
  .dependsOn(`workflows4s-cats` % "compile->compile;test->test")

lazy val `workflows4s-doobie` = (project in file("workflows4s-doobie"))
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "org.tpolecat"  %% "doobie-core"                     % "1.0.0-RC11",
      "com.dimafeng"  %% "testcontainers-scala-scalatest"  % testcontainersScalaVersion % Test,
      "com.dimafeng"  %% "testcontainers-scala-postgresql" % testcontainersScalaVersion % Test,
      "org.postgresql" % "postgresql"                      % "42.7.8"                   % Test,
      "org.xerial"     % "sqlite-jdbc"                     % "3.51.1.0"                 % Test,
    ),
  )
  .dependsOn(`workflows4s-cats` % "compile->compile;test->test")

lazy val `workflows4s-filesystem` = (project in file("workflows4s-filesystem"))
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "co.fs2" %% "fs2-io" % "3.12.2",
    ),
  )
  .dependsOn(`workflows4s-cats` % "compile->compile;test->test")

lazy val `workflows4s-quartz` = (project in file("workflows4s-quartz"))
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "org.quartz-scheduler" % "quartz" % "2.5.2",
    ),
  )
  .dependsOn(`workflows4s-cats` % "compile->compile;test->test")

lazy val `workflows4s-web-api-shared` = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Pure)
  .in(file("workflows4s-web-api-shared"))
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "com.softwaremill.sttp.tapir"   %%% "tapir-core"         % tapirVersion,
      "com.softwaremill.sttp.tapir"   %%% "tapir-json-circe"   % tapirVersion,
      "io.circe"                      %%% "circe-core"         % circeVersion,
      "io.circe"                      %%% "circe-generic"      % circeVersion,
      "com.softwaremill.sttp.tapir"   %%% "tapir-apispec-docs" % tapirVersion,
      "com.softwaremill.sttp.apispec" %%% "jsonschema-circe"   % "0.11.10",
    ),
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
      "com.softwaremill.sttp.tapir"   %%% "tapir-sttp-client4" % "1.13.3",
      "com.softwaremill.sttp.client4" %%% "cats"               % "4.0.13",
      "org.business4s"                %%% "forms4s-jsonschema" % "0.1.0",
      "org.business4s"                %%% "forms4s-tyrian"     % "0.1.0",
      "org.business4s"                %%% "forms4s-circe"      % "0.1.0",
    ),
    scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.ESModule) },
  )
  .dependsOn(`workflows4s-core`, `workflows4s-web-api-shared`.js)

lazy val `workflows4s-web-ui-bundle` = (project in file("workflows4s-web-ui-bundle"))
  .settings(commonSettings)
  .dependsOn(`workflows4s-web-api-shared`.jvm)
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
      "org.http4s"           %% "http4s-ember-server"             % "0.23.33",
      "org.http4s"           %% "http4s-dsl"                      % "0.23.33",
      "org.apache.pekko"     %% "pekko-http"                      % pekkoHttpVersion, // for interacting with the app
      "org.apache.pekko"     %% "pekko-cluster-sharding-typed"    % pekkoVersion, // for realistic example and spawning actors
      "org.apache.pekko"     %% "pekko-persistence-jdbc"          % "1.2.0", // published locally until the release is there
      "org.apache.pekko"     %% "pekko-serialization-jackson"     % "1.4.0",
      "com.h2database"        % "h2"                              % "2.4.240",
      "io.r2dbc"              % "r2dbc-h2"                        % "1.1.0.RELEASE",
      "com.github.pjfanning" %% "pekko-http-circe"                % "3.7.0",
      "ch.qos.logback"        % "logback-classic"                 % "1.5.23",
      "org.scalamock"        %% "scalamock"                       % "7.5.2"                    % Test,
      "org.apache.pekko"     %% "pekko-actor-testkit-typed"       % pekkoVersion               % Test,
      "com.dimafeng"         %% "testcontainers-scala-scalatest"  % testcontainersScalaVersion % Test,
      "com.dimafeng"         %% "testcontainers-scala-postgresql" % testcontainersScalaVersion % Test,
      "org.postgresql"        % "postgresql"                      % "42.7.8"                   % Test,
      "org.xerial"            % "sqlite-jdbc"                     % "3.51.1.0"                 % Test,
    ),
    Test / parallelExecution := false, // otherwise akka clusters clash
    publish / skip           := true,
  )
  .dependsOn(
    `workflows4s-tck`    % "compile->compile;test->test",
    `workflows4s-cats`   % "compile->compile;test->test",
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
    reStart / mainClass             := Some("workflows4s.example.api.Server"),
  )
  .settings(Revolver.enableDebugging(port = 5050))

lazy val commonSettings = Seq(
  scalaVersion      := "3.7.4",
  scalacOptions ++= Seq("-no-indent", "-Xmax-inlines", "64", "-explain-cyclic", "-Ydebug-cyclic"),
  libraryDependencies ++= Seq(
    "org.scalatest" %% "scalatest"       % "3.2.19" % Test,
    "ch.qos.logback" % "logback-classic" % "1.5.23" % Test,
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

lazy val pekkoVersion               = "1.4.0"
lazy val pekkoHttpVersion           = "1.3.0"
lazy val testcontainersScalaVersion = "0.44.1"
lazy val tapirVersion               = "1.13.3"
lazy val circeVersion               = "0.14.15"

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
