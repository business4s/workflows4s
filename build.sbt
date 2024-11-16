lazy val `workflows4s` = (project in file("."))
  .settings(commonSettings)
  .aggregate(`workflows4s-core`, `workflows4s-bpmn`, `workflows4s-example`)

lazy val `workflows4s-core` = (project in file("workflows4s-core"))
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "org.typelevel"              %% "cats-effect"   % "3.5.5",
      // should be required only for simple actor, probably worth separate module
      "com.typesafe.scala-logging" %% "scala-logging" % "3.9.5",
      "io.circe"                   %% "circe-core"    % "0.14.10", // for model serialization
      "io.circe"                   %% "circe-generic" % "0.14.10", // for model serialization
      "com.lihaoyi"                %% "sourcecode"    % "0.4.2",  // for auto naming
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
      "org.apache.pekko" %% "pekko-persistence-typed"     % pekkoVerion,
      "org.apache.pekko" %% "pekko-persistence-testkit"   % pekkoVerion % Test,
    ),
  )
  .dependsOn(`workflows4s-core`)

lazy val `workflows4s-example` = (project in file("workflows4s-example"))
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "org.apache.pekko"     %% "pekko-http"                   % pekkoHttpVerion,                 // for interacting with the app
      "org.apache.pekko"     %% "pekko-cluster-sharding-typed" % pekkoVerion,                     // for realistic example and spawning actors
      "org.apache.pekko"     %% "pekko-persistence-jdbc"       % "1.1.0", // published locally until the release is there
      "org.apache.pekko"     %% "pekko-serialization-jackson"  % "1.1.2",
      "com.h2database"        % "h2"                           % "2.3.232",
      "io.r2dbc"              % "r2dbc-h2"                     % "1.0.0.RELEASE",
      "com.github.pjfanning" %% "pekko-http-circe"             % "3.0.0",
      "ch.qos.logback"        % "logback-classic"              % "1.5.12",
      "org.scalamock"        %% "scalamock"                    % "6.0.0" % Test,
      "org.apache.pekko"     %% "pekko-actor-testkit-typed"    % pekkoVerion % Test,
    ),
  )
  .dependsOn(`workflows4s-core`, `workflows4s-bpmn`, `workflows4s-pekko`)

lazy val commonSettings = Seq(
  scalaVersion := "3.5.2",
  scalacOptions ++= Seq("-no-indent", "-Xmax-inlines", "64"),
  libraryDependencies ++= Seq(
    "org.scalatest" %% "scalatest" % "3.2.19" % Test,
  ),
)

lazy val pekkoVerion     = "1.1.2"
lazy val pekkoHttpVerion = "1.0.1"
