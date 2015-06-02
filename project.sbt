
organization in Global := "oncue.knobs"

scalaVersion in Global := "2.10.5"

scalacOptions in Global := Seq(
  "-deprecation",
  "-feature",
  "-encoding", "utf8",
  "-language:postfixOps",
  "-language:higherKinds",
  "-language:implicitConversions",
  "-unchecked",
  "-Xcheckinit",
  "-Xfuture",
  "-Xlint",
  "-Xfatal-warnings",
  "-Yno-adapted-args",
  "-Ywarn-dead-code",
  "-Ywarn-value-discard")

licenses in Global += ("Apache-2.0", url("https://www.apache.org/licenses/LICENSE-2.0.html"))

lazy val knobs = project.in(file(".")).aggregate(core, docs, typesafe, zookeeper)

lazy val core = project

lazy val docs = project.dependsOn(core)

lazy val typesafe = project.dependsOn(core)

lazy val zookeeper = project.dependsOn(core)

releaseSettings

publishArtifact in (Compile, packageBin) := false

publish := ()

publishLocal := ()
