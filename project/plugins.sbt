addSbtPlugin("org.scalameta"      % "sbt-scalafmt"             % "2.5.5")
addSbtPlugin("ch.epfl.scala"      % "sbt-scalafix"             % "0.14.3")
addSbtPlugin("com.github.sbt"     % "sbt-dynver"               % "5.1.1")
addSbtPlugin("com.github.sbt"     % "sbt-ci-release"           % "1.11.2")
addSbtPlugin("org.typelevel"      % "sbt-tpolecat"             % "0.5.2")
addSbtPlugin("org.scala-js"       % "sbt-scalajs"              % "1.19.0")
addSbtPlugin("com.github.sbt"     % "sbt-native-packager"      % "1.11.1")
addSbtPlugin("io.spray"           % "sbt-revolver"             % "0.10.0")
addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject" % "1.3.2")

libraryDependencies ++= Seq(
  "com.lihaoyi" %% "os-lib" % "0.11.4",
)
