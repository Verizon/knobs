
import sbt._, Keys._
import sbtrelease._
import sbtrelease.ReleasePlugin.autoImport._
import sbtrelease.ReleaseStateTransformations._
import sbtrelease.Utilities._
import com.typesafe.sbt.pgp.PgpKeys._
import bintray.BintrayKeys._

object common {

  def settings =
    bintraySettings ++
    releaseSettings ++
    publishingSettings ++
    testSettings ++
    customSettings

  val scalaTestVersion    = SettingKey[String]("scalatest version")
  val scalaCheckVersion   = SettingKey[String]("scalacheck version")
  val scalazStreamVersion = SettingKey[String]("scalaz stream version")

  def testSettings = Seq(
    scalaTestVersion     := "2.2.5",
    scalaCheckVersion    := "1.12.3",
    libraryDependencies ++= Seq(
      "org.scalatest"  %% "scalatest"  % scalaTestVersion.value  % "test",
      "org.scalacheck" %% "scalacheck" % scalaCheckVersion.value % "test"
    )
  )

  def ignore = Seq(
    publish := (),
    publishSigned := (),
    publishLocal := (),
    publishLocalSigned := (),
    publishArtifact in Test := false,
    publishArtifact in Compile := false
  )

  def bintraySettings = Seq(
    bintrayPackageLabels := Seq("configuration", "functional programming", "scala", "reasonable"),
    bintrayOrganization := Some("oncue"),
    bintrayRepository := "releases",
    bintrayPackage := "knobs"
  )

  def releaseSettings = Seq(
    releaseCrossBuild := true,
    releasePublishArtifactsAction := publishSigned.value,
    releaseProcess := Seq[ReleaseStep](
      checkSnapshotDependencies,
      inquireVersions,
      runTest,
      setReleaseVersion,
      commitReleaseVersion,
      tagRelease,
      publishArtifacts,
      setNextVersion,
      commitNextVersion,
      pushChanges
    )
  )

  def publishingSettings = Seq(
    pomExtra := (
      <developers>
        <developer>
          <id>timperrett</id>
          <name>Timothy Perrett</name>
          <url>http://github.com/timperrett</url>
        </developer>
        <developer>
          <id>runarorama</id>
          <name>Runar Bjarnason</name>
          <url>http://github.com/runarorama</url>
        </developer>
        <developer>
          <id>stew</id>
          <name>Stew O'Connor</name>
          <url>http://github.com/stew</url>
        </developer>
      </developers>),
    publishMavenStyle := true,
    useGpg := true,
    licenses := Seq("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0.html")),
    homepage := Some(url("http://oncue.github.io/knobs/")),
    scmInfo := Some(ScmInfo(url("https://github.com/oncue/knobs"),
                                "git@github.com:oncue/knobs.git")),
    pomIncludeRepository := { _ => false },
    publishArtifact in Test := false
  )

  def customSettings = Seq(
    // "0.8.1a" "0.7.3a"
    scalazStreamVersion := {
      if(sys.env.get("TRAVIS").nonEmpty){
        sys.env("SCALAZ_STREAM_VERSION")
      } else {
        "0.7.3a"
      }
    },
    unmanagedSourceDirectories in Compile += (sourceDirectory in Compile).value / s"scalaz-stream-${scalazStreamVersion.value.take(3)}"
  )
}
