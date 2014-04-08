
import oncue.build._

organization := "oncue.svc.knobs"

scalaVersion := "2.10.4"

lazy val knobs = project.in(file(".")).aggregate(core, typesafe)

lazy val core = project

lazy val typesafe = project.dependsOn(core)

publishArtifact in (Compile, packageBin) := false

publish := ()

publishLocal := ()

OnCue.baseSettings
