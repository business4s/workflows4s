lazy val `workflow4s` = (project in file("."))
  .settings(
    scalaVersion := "2.13.10",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % "3.4.11",
      "org.scalatest" %% "scalatest"   % "3.2.15"
    )
  )