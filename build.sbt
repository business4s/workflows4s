lazy val `workflow4s` = (project in file("."))
  .settings(
    scalaVersion := "2.13.10",
    libraryDependencies ++= Seq(
      "org.typelevel"              %% "cats-effect"     % "3.5.3",
      // should be required only for simple actor, probably worh separate module
      "com.typesafe.scala-logging" %% "scala-logging"   % "3.9.5",
      "org.scalatest"              %% "scalatest"       % "3.2.17" % Test,
      "ch.qos.logback"              % "logback-classic" % "1.4.14" % Test,
    ),
  )
