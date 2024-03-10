lazy val `workflow4s`      = (project in file("."))
  .settings(
    scalaVersion := "2.13.10",
    libraryDependencies ++= Seq(
      "org.typelevel"              %% "cats-effect"     % "3.5.3",
      // should be required only for simple actor, probably worth separate module
      "com.typesafe.scala-logging" %% "scala-logging"   % "3.9.5",
      "org.scalatest"              %% "scalatest"       % "3.2.17" % Test,
      "ch.qos.logback"              % "logback-classic" % "1.4.14" % Test,
      "io.circe"                   %% "circe-core"      % "0.15.0-M1", // for model serialization
      "io.circe"                   %% "circe-generic"   % "0.15.0-M1", // for model serialization
      "com.lihaoyi"                %% "sourcecode"      % "0.3.1", // for auto naming
    ),
    scalacOptions := Seq(
      "-Ymacro-annotations",
    ),
  )
lazy val `workflow4s-bpmn` = (project in file("workflow4s-bpmn"))
  .settings(
    scalaVersion := "2.13.10",
    libraryDependencies ++= Seq(
      "org.scalatest"        %% "scalatest"          % "3.2.17" % Test,
      "ch.qos.logback"        % "logback-classic"    % "1.4.14" % Test,
      "org.camunda.bpm.model" % "camunda-bpmn-model" % "7.20.0",
    ),
  )
  .dependsOn(`workflow4s`)

lazy val `workflow4s-example` = (project in file("workflow4s-example"))
  .settings(
    scalaVersion := "2.13.10",
    libraryDependencies ++= Seq(
      "org.scalatest" %% "scalatest"       % "3.2.17" % Test,
      "org.scalamock" %% "scalamock"       % "5.1.0"  % Test,
      "ch.qos.logback" % "logback-classic" % "1.4.14" % Test,
    ),
  )
  .dependsOn(`workflow4s`, `workflow4s-bpmn`)
