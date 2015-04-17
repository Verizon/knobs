
import oncue.build._

organization in Global := "oncue.svc.knobs"

scalaVersion in Global := "2.10.4"

scalacOptions in Global := Seq(
  "-deprecation",
  "-feature",
  "-encoding", "utf8",
  "-language:postfixOps",
  "-language:higherKinds",
  "-language:implicitConversions",
  "-target:jvm-1.7",
  "-unchecked",
  "-Xcheckinit",
  "-Xfuture",
  "-Xlint",
  "-Xfatal-warnings",
  "-Yno-adapted-args",
  "-Ywarn-dead-code",
  "-Ywarn-value-discard")

lazy val knobs = project.in(file(".")).aggregate(core, typesafe, zookeeper)

lazy val core = project

lazy val typesafe = project.dependsOn(core)

lazy val zookeeper = project.dependsOn(core)

publishArtifact in (Compile, packageBin) := false

publish := ()

publishLocal := ()

OnCue.baseSettings
