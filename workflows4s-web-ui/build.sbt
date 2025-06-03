import scala.sys.process._
import scala.language.postfixOps

import sbtwelcome._

Global / onChangedBuildSource := ReloadOnSourceChanges

lazy val workflows4s-web-ui =
  (project in file("."))
    .enablePlugins(ScalaJSPlugin)
    .settings( // Normal settings
      name         := "workflows4s-web-ui",
      version      := "0.0.1",
      scalaVersion := "3.6.4",
      organization := "myorg",
      libraryDependencies ++= Seq(
        "io.indigoengine" %%% "tyrian-io" % "0.14.0",
        "org.scalameta"   %%% "munit"     % "1.1.1" % Test
      ),
      testFrameworks += new TestFramework("munit.Framework"),
      scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.CommonJSModule) },
      scalafixOnCompile := true,
      semanticdbEnabled := true,
      semanticdbVersion := scalafixSemanticdb.revision,
      autoAPIMappings   := true
    )
    .settings( // Welcome message
      logo := List(
        "",
        "workflows4s-web-ui (v" + version.value + ")",
        ""
      ).mkString("\n"),
      usefulTasks := Seq(
        UsefulTask("fastLinkJS", "Rebuild the JS (use during development)").noAlias,
        UsefulTask("fullLinkJS", "Rebuild the JS and optimise (use in production)").noAlias
      ),
      logoColor        := scala.Console.MAGENTA,
      aliasColor       := scala.Console.BLUE,
      commandColor     := scala.Console.CYAN,
      descriptionColor := scala.Console.WHITE
    )
