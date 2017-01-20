organization in Global := "io.verizon.knobs"

crossScalaVersions in Global := Seq("2.12.1", "2.11.8", "2.10.6")

scalaVersion in Global := crossScalaVersions.value.head

lazy val knobs = project.in(file(".")).aggregate(core, typesafe, zookeeper, docs)

lazy val core = project

lazy val typesafe = project.dependsOn(core)

lazy val zookeeper = project.dependsOn(core)

lazy val docs = project.dependsOn(core, zookeeper)

enablePlugins(DisablePublishingPlugin)
