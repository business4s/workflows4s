import org.typelevel.scalacoptions.ScalacOptions

lazy val websiteScaladocs = taskKey[Unit]("Generate unified scaladoc and copy to website/static/scaladoc")

lazy val `workflows4s` = (project in file("."))
  .enablePlugins(ScalaUnidocPlugin)
  .settings(commonSettings)
  .settings(
    ScalaUnidoc / unidoc / unidocProjectFilter := inAnyProject -- inProjects(
      `workflows4s-example`,
      `workflows4s-web-ui`,
      `workflows4s-web-ui-bundle`,
      `workflows4s-web-api-shared`.js(scala3Version),
    ),
    websiteScaladocs                           := {
      val log       = streams.value.log
      val base      = (ThisBuild / baseDirectory).value
      val targetDir = base / "website" / "static" / "scaladoc"
      val _         = (Compile / unidoc).value
      val docDir    = crossTarget.value / "unidoc"

      log.info(s"Copying unified scaladoc from $docDir to $targetDir")
      IO.delete(targetDir)
      IO.copyDirectory(docDir, targetDir)
      log.info(s"Unified scaladoc copied to $targetDir")
    },
  )
  .aggregate(
    `workflows4s-core`,
    `workflows4s-cats-effect`,
    `workflows4s-bpmn`,
    `workflows4s-pekko`,
    `workflows4s-example`,
    `workflows4s-doobie`,
    `workflows4s-filesystem`,
    `workflows4s-quartz`,
    `workflows4s-web-ui`,
    `workflows4s-web-ui-bundle`,
    `workflows4s-web-api-shared`.js(scala3Version),
    `workflows4s-web-api-shared`.jvm(scala3Version),
    `workflows4s-web-api-server`,
  )

lazy val `workflows4s-core` = (project in file("workflows4s-core"))
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "org.typelevel"              %% "cats-core"        % "2.13.0",
      "com.typesafe.scala-logging" %% "scala-logging"    % "3.9.6",
      "io.circe"                   %% "circe-core"       % circeVersion, // for model serialization
      "io.circe"                   %% "circe-generic"    % circeVersion, // for model serialization
      "com.lihaoyi"                %% "sourcecode"       % "0.4.4", // for auto naming
      "org.typelevel"              %% "cats-effect"      % "3.7.1"     % Test,
      "dev.zio"                    %% "zio"              % "2.1.26"    % Test,
      "dev.zio"                    %% "zio-interop-cats" % "23.1.0.13" % Test,
      "ch.qos.logback"              % "logback-classic"  % "1.6.3"     % Test,
    ),
    Test / parallelExecution := false,
  )

lazy val `workflows4s-cats-effect` = (project in file("workflows4s-cats-effect"))
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % "3.7.1",
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
      "io.altoo"         %% "pekko-kryo-serialization"     % "1.5.2",
    ),
  )
  .dependsOn(`workflows4s-core` % "compile->compile;test->test")

lazy val `workflows4s-doobie` = (project in file("workflows4s-doobie"))
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "org.typelevel" %% "doobie-core"                     % "1.0.0-RC13",
      "io.circe"      %% "circe-parser"                    % circeVersion,
      "com.dimafeng"  %% "testcontainers-scala-scalatest"  % testcontainersScalaVersion % Test,
      "com.dimafeng"  %% "testcontainers-scala-postgresql" % testcontainersScalaVersion % Test,
      "org.postgresql" % "postgresql"                      % "42.7.13"                  % Test,
      "org.xerial"     % "sqlite-jdbc"                     % "3.53.4.0"                 % Test,
    ),
  )
  .dependsOn(`workflows4s-core` % "compile->compile;test->test")

lazy val `workflows4s-filesystem` = (project in file("workflows4s-filesystem"))
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "co.fs2" %% "fs2-io" % "3.13.0",
    ),
  )
  .dependsOn(`workflows4s-core` % "compile->compile;test->test")

lazy val `workflows4s-quartz` = (project in file("workflows4s-quartz"))
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "org.typelevel"       %% "cats-effect" % "3.7.1",
      "org.quartz-scheduler" % "quartz"      % "2.5.2",
    ),
  )
  .dependsOn(`workflows4s-core` % "compile->compile;test->test")

lazy val `workflows4s-web-api-shared` = (projectMatrix in file("workflows4s-web-api-shared"))
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "com.softwaremill.sttp.tapir"   %% "tapir-core"         % tapirVersion,
      "com.softwaremill.sttp.tapir"   %% "tapir-json-circe"   % tapirVersion,
      "io.circe"                      %% "circe-core"         % circeVersion,
      "io.circe"                      %% "circe-generic"      % circeVersion,
      "com.softwaremill.sttp.tapir"   %% "tapir-apispec-docs" % tapirVersion,
      "com.softwaremill.sttp.apispec" %% "jsonschema-circe"   % "0.11.10",
    ),
  )
  .jvmPlatform(scalaVersions = Seq(scala3Version))
  .jsPlatform(scalaVersions = Seq(scala3Version))

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
    `workflows4s-web-api-shared`.jvm(scala3Version),
  )

lazy val `workflows4s-web-ui` = (project in file("workflows4s-web-ui"))
  .enablePlugins(ScalaJSPlugin)
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "io.indigoengine"               %% "tyrian-io"          % "0.14.0",
      "io.circe"                      %% "circe-core"         % circeVersion,
      "io.circe"                      %% "circe-generic"      % circeVersion,
      "io.circe"                      %% "circe-parser"       % circeVersion,
      "com.softwaremill.sttp.tapir"   %% "tapir-sttp-client4" % "1.13.31",
      "com.softwaremill.sttp.client4" %% "cats"               % "4.0.26",
      "org.business4s"                %% "forms4s-jsonschema" % "0.2.0",
      "org.business4s"                %% "forms4s-tyrian"     % "0.2.0",
      "org.business4s"                %% "forms4s-circe"      % "0.2.0",
    ),
    scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.ESModule) },
  )
  .dependsOn(`workflows4s-core`, `workflows4s-web-api-shared`.js(scala3Version))

lazy val `workflows4s-web-ui-bundle` = (project in file("workflows4s-web-ui-bundle"))
  .settings(commonSettings)
  .dependsOn(`workflows4s-web-api-shared`.jvm(scala3Version))
  .settings(
    name := "workflows4s-web-ui-bundle",
    libraryDependencies ++= Seq(
      "com.softwaremill.sttp.tapir" %% "tapir-files" % tapirVersion,
    ),
    // Def.uncached: sbt 2 would otherwise cache this task by its return value alone and could
    // skip it without materializing the files; skipping is instead handled by FileFunction.cached,
    // which also checks that the outputs exist.
    Compile / resourceGenerators += Def.task(Def.uncached {
      val log         = streams.value.log
      val cacheDir    = streams.value.cacheDirectory / "webui-bundleit-cache"
      val webUiDir    = (`workflows4s-web-ui` / baseDirectory).value
      val jsOutputDir = (`workflows4s-web-ui` / Compile / fullLinkJSOutput).value
      val outputDir   = (Compile / resourceManaged).value

      // linked js + the frontend sources vite consumes directly
      val inputs = Set(jsOutputDir / "main.js") ++
        (webUiDir * ("*.html" || "*.js" || "*.mjs" || "*.css" || "*.json")).get() ++
        (webUiDir / "public").allPaths.get()

      val cached = FileFunction.cached(cacheDir, FilesInfo.hash) { _ =>
        log.info("Bundling webui due to source change or missing output.")
        BundleIt.bundle(
          from = webUiDir,
          to = outputDir,
          scalaJSOutputDir = jsOutputDir,
        )
      }
      cached(inputs).toSeq
    }),
  )

lazy val `workflows4s-example` = (project in file("workflows4s-example"))
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "org.http4s"             %% "http4s-ember-server"             % "0.23.36",
      "org.http4s"             %% "http4s-dsl"                      % "0.23.36",
      "org.apache.pekko"       %% "pekko-http"                      % pekkoHttpVersion, // for interacting with the app
      "org.apache.pekko"       %% "pekko-cluster-sharding-typed"    % pekkoVersion, // for realistic example and spawning actors
      "org.apache.pekko"       %% "pekko-persistence-jdbc"          % "1.3.0", // published locally until the release is there
      "org.apache.pekko"       %% "pekko-serialization-jackson"     % "1.7.0",
      "com.h2database"          % "h2"                              % "2.4.240",
      "io.r2dbc"                % "r2dbc-h2"                        % "1.1.0.RELEASE",
      "com.github.pjfanning"   %% "pekko-http-circe"                % "3.10.1",
      "ch.qos.logback"          % "logback-classic"                 % "1.6.3",
      "dev.zio"                %% "zio"                             % "2.1.26",
      "dev.zio"                %% "zio-interop-cats"                % "23.1.0.3",
      "org.scalamock"          %% "scalamock"                       % "7.5.5"                    % Test,
      "org.apache.pekko"       %% "pekko-actor-testkit-typed"       % pekkoVersion               % Test,
      "com.dimafeng"           %% "testcontainers-scala-scalatest"  % testcontainersScalaVersion % Test,
      "com.dimafeng"           %% "testcontainers-scala-postgresql" % testcontainersScalaVersion % Test,
      "org.postgresql"          % "postgresql"                      % "42.7.13"                  % Test,
      "org.xerial"              % "sqlite-jdbc"                     % "3.53.4.0"                 % Test,
      "org.seleniumhq.selenium" % "selenium-java"                   % "4.48.0"                   % Test,
      "org.seleniumhq.selenium" % "selenium-chrome-driver"          % "4.48.0"                   % Test,
    ),
    Test / parallelExecution := false, // otherwise akka clusters clash
    publish / skip           := true,
  )
  .dependsOn(
    `workflows4s-core`        % "compile->compile;test->test",
    `workflows4s-cats-effect` % "compile->compile;test->test",
    `workflows4s-bpmn`,
    `workflows4s-pekko`       % "compile->compile;test->test",
    `workflows4s-doobie`      % "compile->compile;test->test",
    `workflows4s-filesystem`,
    `workflows4s-quartz`,
    `workflows4s-web-api-server`,
    `workflows4s-web-ui-bundle`,
    // no dependency on `workflows4s-web-ui` itself: a JVM app cannot use Scala.js classes, and
    // its sjs jars collide with the JVM ones when staged for Docker; the UI arrives via the bundle
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
  // fully qualified: the sbt-revolver fork auto-imports an ambiguous second `Revolver` object
  .settings(spray.revolver.RevolverPlugin.autoImport.Revolver.enableDebugging(port = 5050))

lazy val scala3Version = "3.8.4"

lazy val commonSettings = Seq(
  scalaVersion      := scala3Version,
  scalacOptions ++= Seq("-no-indent", "-Xmax-inlines", "64", "-explain-cyclic", "-Ydebug-cyclic"),
  libraryDependencies ++= Seq(
    "org.scalatest" %% "scalatest"       % "3.2.20" % Test,
    "ch.qos.logback" % "logback-classic" % "1.6.3"  % Test,
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

lazy val pekkoVersion               = "1.7.0"
lazy val pekkoHttpVersion           = "1.4.0"
lazy val testcontainersScalaVersion = "0.44.1"
lazy val tapirVersion               = "1.13.31"
lazy val circeVersion               = "0.14.16"

addCommandAlias("prePR", List("compile", "Test / compile", "test", "scalafmtCheckAll").mkString(";", ";", ""))

lazy val stableVersion = taskKey[String]("stableVersion")
stableVersion := {
  if (isVersionStable.value && !isSnapshot.value) version.value
  else previousStableVersion.value.getOrElse("unreleased")
}

lazy val stableVersionFile = settingKey[File]("File that writeStableVersion writes to")
stableVersionFile := (ThisBuild / baseDirectory).value / "target" / "stable-version.txt"

// Writing to a file instead of printing, because sbt's stdout is not machine-readable
// (ANSI escapes, progress lines, launcher output). Consumed by the website build.
lazy val writeStableVersion = taskKey[Unit]("Writes stableVersion to stableVersionFile")
writeStableVersion := {
  val target = stableVersionFile.value
  IO.write(target, stableVersion.value)
  streams.value.log.info(s"Wrote stable version to $target")
}

ThisBuild / publishTo := {
  val centralSnapshots = "https://central.sonatype.com/repository/maven-snapshots/"
  if (isSnapshot.value) Some("central-snapshots" at centralSnapshots)
  else localStaging.value
}

Global / onChangedBuildSource := ReloadOnSourceChanges

// required for docker tags
ThisBuild / dynverSeparator := "-"
