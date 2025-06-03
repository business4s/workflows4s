import org.typelevel.scalacoptions.ScalacOptions

lazy val `workflows4s` = (project in file("."))
  .settings(commonSettings)
  .aggregate(
    `workflows4s-core`,
    `workflows4s-bpmn`,
    `workflows4s-pekko`,
    `workflows4s-example`,
    `workflows4s-doobie`,
    `workflows4s-filesystem`,
    `workflows4s-quartz`,
  )

lazy val `workflows4s-core` = (project in file("workflows4s-core"))
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "org.typelevel"              %% "cats-effect"     % "3.6.1",
      "co.fs2"                     %% "fs2-core"        % "3.12.0",
      "com.typesafe.scala-logging" %% "scala-logging"   % "3.9.5",
      "io.circe"                   %% "circe-core"      % "0.14.13", // for model serialization
      "io.circe"                   %% "circe-generic"   % "0.14.13", // for model serialization
      "com.lihaoyi"                %% "sourcecode"      % "0.4.2", // for auto naming
      "ch.qos.logback"              % "logback-classic" % "1.5.18" % Test,
    ),
  )

lazy val `workflows4s-bpmn` = (project in file("workflows4s-bpmn"))
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "org.camunda.bpm.model" % "camunda-bpmn-model" % "7.23.0",
    ),
  )
  .dependsOn(`workflows4s-core`)

lazy val `workflows4s-pekko` = (project in file("workflows4s-pekko"))
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "org.apache.pekko" %% "pekko-persistence-typed"      % pekkoVersion,
      "org.apache.pekko" %% "pekko-cluster-typed"          % pekkoVersion,
      "org.apache.pekko" %% "pekko-cluster-sharding-typed" % pekkoVersion,
      "org.apache.pekko" %% "pekko-persistence-testkit"    % pekkoVersion    % Test,
      "org.apache.pekko" %% "pekko-persistence-jdbc"       % "1.1.0"         % Test,
      "com.h2database"    % "h2"                           % "2.3.232"       % Test,
      "io.r2dbc"          % "r2dbc-h2"                     % "1.0.0.RELEASE" % Test,
    ),
  )
  .dependsOn(`workflows4s-core` % "compile->compile;test->test")

lazy val `workflows4s-doobie` = (project in file("workflows4s-doobie"))
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "org.tpolecat"  %% "doobie-core"                     % "1.0.0-RC9",
      "com.dimafeng"  %% "testcontainers-scala-scalatest"  % testcontainersScalaVersion % Test,
      "com.dimafeng"  %% "testcontainers-scala-postgresql" % testcontainersScalaVersion % Test,
      "org.postgresql" % "postgresql"                      % "42.7.5"                   % Test,
    ),
  )
  .dependsOn(`workflows4s-core` % "compile->compile;test->test")

lazy val `workflows4s-filesystem` = (project in file("workflows4s-filesystem"))
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "co.fs2"        %% "fs2-io"          % "3.12.0",
      "ch.qos.logback" % "logback-classic" % "1.5.18" % Test,
    ),
  )
  .dependsOn(`workflows4s-core` % "compile->compile;test->test")

lazy val `workflows4s-quartz` = (project in file("workflows4s-quartz"))
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "org.quartz-scheduler" % "quartz"          % "2.5.0",
      "ch.qos.logback"       % "logback-classic" % "1.5.14" % Test,
    ),
  )
  .dependsOn(`workflows4s-core`)

lazy val `workflows4s-example` = (project in file("workflows4s-example"))
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "org.apache.pekko"     %% "pekko-http"                      % pekkoHttpVersion, // for interacting with the app
      "org.apache.pekko"     %% "pekko-cluster-sharding-typed"    % pekkoVersion, // for realistic example and spawning actors
      "org.apache.pekko"     %% "pekko-persistence-jdbc"          % "1.1.1", // published locally until the release is there
      "org.apache.pekko"     %% "pekko-serialization-jackson"     % "1.1.3",
      "com.h2database"        % "h2"                              % "2.3.232",
      "io.r2dbc"              % "r2dbc-h2"                        % "1.0.0.RELEASE",
      "com.github.pjfanning" %% "pekko-http-circe"                % "3.2.0",
      "ch.qos.logback"        % "logback-classic"                 % "1.5.18",
      "org.scalamock"        %% "scalamock"                       % "7.3.2"                    % Test,
      "org.apache.pekko"     %% "pekko-actor-testkit-typed"       % pekkoVersion               % Test,
      "com.dimafeng"         %% "testcontainers-scala-scalatest"  % testcontainersScalaVersion % Test,
      "com.dimafeng"         %% "testcontainers-scala-postgresql" % testcontainersScalaVersion % Test,
      "org.postgresql"        % "postgresql"                      % "42.7.5"                   % Test,
    ),
    Test / parallelExecution := false, // otherwise akka clusters clash
    publish / skip           := true,
  )
  .dependsOn(
    `workflows4s-core`   % "compile->compile;test->test",
    `workflows4s-bpmn`,
    `workflows4s-pekko`  % "compile->compile;test->test",
    `workflows4s-doobie` % "compile->compile;test->test",
    `workflows4s-filesystem`,
    `workflows4s-quartz`,
  )

lazy val commonSettings = Seq(
  scalaVersion      := "3.7.0",
  scalacOptions ++= Seq("-no-indent", "-Xmax-inlines", "64", "-explain-cyclic", "-Ydebug-cyclic"),
  libraryDependencies ++= Seq(
    "org.scalatest" %% "scalatest" % "3.2.19" % Test,
  ),
  // scalafix settings
  semanticdbEnabled := true, // enable SemanticDB
  organization      := "org.business4s",
  homepage          := Some(url("https://business4s.github.io/workflows4s/")),
  licenses          := List(License.MIT),
  developers        := List(
    Developer(
      "Krever",
      "Voytek Pituła",
      "w.pitula@gmail.com",
      url("https://v.pitula.me"),
    ),
  ),
  versionScheme     := Some("semver-spec"),
  Test / tpolecatExcludeOptions += ScalacOptions.warnNonUnitStatement,
)

lazy val pekkoVersion               = "1.1.3"
lazy val pekkoHttpVersion           = "1.2.0"
lazy val testcontainersScalaVersion = "0.43.0"

addCommandAlias("prePR", List("compile", "Test / compile", "test", "scalafmtCheckAll").mkString(";", ";", ""))

lazy val stableVersion = taskKey[String]("stableVersion")

stableVersion := {
  if (isVersionStable.value && !isSnapshot.value) version.value
  else previousStableVersion.value.getOrElse("unreleased")
}

ThisBuild / publishTo := {
  val centralSnapshots = "https://central.sonatype.com/repository/maven-snapshots/"
  if (isSnapshot.value) Some("central-snapshots" at centralSnapshots)
  else localStaging.value
}
