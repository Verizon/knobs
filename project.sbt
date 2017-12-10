//: ----------------------------------------------------------------------------
//: Copyright (C) 2017 Verizon.  All Rights Reserved.
//:
//:   Licensed under the Apache License, Version 2.0 (the "License");
//:   you may not use this file except in compliance with the License.
//:   You may obtain a copy of the License at
//:
//:       http://www.apache.org/licenses/LICENSE-2.0
//:
//:   Unless required by applicable law or agreed to in writing, software
//:   distributed under the License is distributed on an "AS IS" BASIS,
//:   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//:   See the License for the specific language governing permissions and
//:   limitations under the License.
//:
//: ----------------------------------------------------------------------------
organization in Global := "io.verizon.knobs"

crossScalaVersions in Global := Seq("2.12.4", "2.11.12")

scalaVersion in Global := crossScalaVersions.value.head

scalacOptions in Global := Seq("-Ypartial-unification")

lazy val knobs = project.in(file(".")).aggregate(core, typesafe, zookeeper, docs)

lazy val core = project

lazy val typesafe = project.dependsOn(core)

lazy val zookeeper = project.dependsOn(core)

lazy val docs = project.dependsOn(core, zookeeper)

enablePlugins(DisablePublishingPlugin)

// Some tests set system properties, which results in a
// ConcurrentModificationException while other tests are iterating
// over sys.props.  For a stable build, we need to reduce test
// concurrency to 1 across modules.
concurrentRestrictions in Global += Tags.limit(Tags.Test, 1)
