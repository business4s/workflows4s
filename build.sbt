lazy val `workflows4s` = (project in file("."))
  .settings(commonSettings)
  .aggregate(`workflows4s-core`, `workflows4s-bpmn`, `workflows4s-example`)

lazy val `workflows4s-core` = (project in file("workflows4s-core"))
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "org.typelevel"              %% "cats-effect"     % "3.5.3",
      // should be required only for simple actor, probably worth separate module
      "com.typesafe.scala-logging" %% "scala-logging"   % "3.9.5",
      "io.circe"                   %% "circe-core"      % "0.14.6", // for model serialization
      "io.circe"                   %% "circe-generic"   % "0.14.6", // for model serialization
      "com.lihaoyi"                %% "sourcecode"      % "0.3.1", // for auto naming
    ),
  )

lazy val `workflows4s-bpmn` = (project in file("workflows4s-bpmn"))
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "org.camunda.bpm.model" % "camunda-bpmn-model" % "7.20.0",
    ),
  )
  .dependsOn(`workflows4s-core`)

lazy val `workflows4s-example` = (project in file("workflows4s-example"))
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "org.scalamock" %% "scalamock"       % "6.0.0-M2" % Test,
      "ch.qos.logback" % "logback-classic" % "1.4.14"   % Test,
    ),
  )
  .dependsOn(`workflows4s-core`, `workflows4s-bpmn`)

lazy val commonSettings = Seq(
  scalaVersion := "3.3.3",
  scalacOptions ++= Seq("-no-indent"),
  libraryDependencies ++= Seq(
    "org.scalatest" %% "scalatest" % "3.2.17" % Test,
  ),
)
