lazy val `workflow4s` = (project in file("."))
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
    ),
    scalacOptions := Seq(
      "-Ymacro-annotations"
    )
  )
