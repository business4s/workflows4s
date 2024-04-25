lazy val `workflows4s` = (project in file("."))
  .settings(commonSettings)
  .aggregate(`workflows4s-core`, `workflows4s-bpmn`, `workflows4s-example`)

lazy val `workflows4s-core` = (project in file("workflows4s-core"))
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "org.typelevel"              %% "cats-effect"   % "3.5.3",
      // should be required only for simple actor, probably worth separate module
      "com.typesafe.scala-logging" %% "scala-logging" % "3.9.5",
      "io.circe"                   %% "circe-core"    % "0.14.6", // for model serialization
      "io.circe"                   %% "circe-generic" % "0.14.6", // for model serialization
      "com.lihaoyi"                %% "sourcecode"    % "0.3.1",  // for auto naming
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

lazy val `workflows4s-pekko` = (project in file("workflows4s-pekko"))
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "org.apache.pekko" %% "pekko-persistence-typed"   % pekkoVerion,
      "org.apache.pekko" %% "pekko-persistence-testkit" % pekkoVerion % Test,
    ),
  )
  .dependsOn(`workflows4s-core`)

lazy val `workflows4s-example` = (project in file("workflows4s-example"))
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "org.apache.pekko"     %% "pekko-http"                   % pekkoHttpVerion,                 // for interacting with the app
      "org.apache.pekko"     %% "pekko-cluster-sharding-typed" % pekkoVerion,                     // for realistic example and spawning actors
      "org.apache.pekko"     %% "pekko-persistence-jdbc"       % "1.1.0-M0+60-8e170c21-SNAPSHOT", // published locally until the release is there
      "com.h2database"        % "h2"                           % "2.2.224",
      "io.r2dbc"              % "r2dbc-h2"                     % "1.0.0.RELEASE",
      "com.github.pjfanning" %% "pekko-http-circe"             % "2.4.0",
      "org.scalamock"        %% "scalamock"                    % "6.0.0-M2" % Test,
      "ch.qos.logback"        % "logback-classic"              % "1.4.14",
    ),
  )
  .dependsOn(`workflows4s-core`, `workflows4s-bpmn`, `workflows4s-pekko`)

lazy val commonSettings = Seq(
  scalaVersion := "3.3.3",
  scalacOptions ++= Seq("-no-indent"),
  libraryDependencies ++= Seq(
    "org.scalatest" %% "scalatest" % "3.2.17" % Test,
  ),
)

lazy val pekkoVerion     = "1.0.2"
lazy val pekkoHttpVerion = "1.0.1"
