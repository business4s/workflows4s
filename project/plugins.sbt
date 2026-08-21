addSbtPlugin("org.scalameta"     % "sbt-scalafmt"        % "2.6.2")
addSbtPlugin("ch.epfl.scala"     % "sbt-scalafix"        % "0.14.7")
addSbtPlugin("com.github.sbt"    % "sbt-dynver"          % "5.1.1")
addSbtPlugin("com.github.sbt"    % "sbt-ci-release"      % "1.12.1")
addSbtPlugin("org.typelevel"     % "sbt-tpolecat"        % "0.5.7")
addSbtPlugin("org.scala-js"      % "sbt-scalajs"         % "1.22.0")
addSbtPlugin("com.github.sbt"    % "sbt-native-packager" % "1.11.7")
// fork of io.spray sbt-revolver, with sbt 2 support (upstream has no sbt 2 release yet)
addSbtPlugin("com.indoorvivants" % "sbt-revolver"        % "0.11.2")
// sbt-scalajs-crossproject removed: sbt 2 has cross-platform support built in (projectMatrix)
addSbtPlugin("com.github.sbt"    % "sbt-unidoc"          % "0.6.1")

libraryDependencies ++= Seq(
  "com.lihaoyi" %% "os-lib" % "0.11.8",
)
