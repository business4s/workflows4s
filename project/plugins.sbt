addSbtPlugin("org.scalameta"      % "sbt-scalafmt"             % "2.5.6")
addSbtPlugin("ch.epfl.scala"      % "sbt-scalafix"             % "0.14.5")
addSbtPlugin("com.github.sbt"     % "sbt-dynver"               % "5.1.1")
addSbtPlugin("com.github.sbt"     % "sbt-ci-release"           % "1.11.2")
addSbtPlugin("org.typelevel"      % "sbt-tpolecat"             % "0.5.2")
addSbtPlugin("org.scala-js"       % "sbt-scalajs"              % "1.20.1")
addSbtPlugin("com.github.sbt"     % "sbt-native-packager"      % "1.11.4")
addSbtPlugin("io.spray"           % "sbt-revolver"             % "0.10.0")
addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject" % "1.3.2")

libraryDependencies ++= Seq(
  "com.lihaoyi" %% "os-lib" % "0.11.6",
)
