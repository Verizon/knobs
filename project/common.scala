
import sbt._, Keys._
import sbtrelease._
import sbtrelease.ReleasePlugin.autoImport._
import sbtrelease.ReleaseStateTransformations._
import sbtrelease.Utilities._
import bintray.BintrayKeys._

object common {

  def settings =
    bintraySettings ++
    releaseSettings ++
    publishingSettings ++
    testSettings ++
    customSettings

  val scalaTestVersion    = settingKey[String]("scalatest version")
  val scalaCheckVersion   = settingKey[String]("scalacheck version")
  val scalazStreamVersion = settingKey[String]("scalaz stream version")

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
    publishLocal := (),
    publishArtifact in Test := false,
    publishArtifact in Compile := false
  )

  def bintraySettings = Seq(
    bintrayPackageLabels := Seq("configuration", "functional programming", "scala", "reasonable"),
    bintrayOrganization := Some("oncue"),
    bintrayRepository := "releases",
    bintrayPackage := "knobs"
  )

  def withoutSnapshot(ver: Version) =
    if(ver.qualifier.exists(_ == "-SNAPSHOT")) ver.withoutQualifier
    else ver.copy(qualifier = ver.qualifier.map(_.replaceAll("-SNAPSHOT", "")))

  def releaseSettings = Seq(
    releaseCrossBuild := false,
    releaseVersion := { ver =>
      sys.env.get("TRAVIS_BUILD_NUMBER").orElse(sys.env.get("BUILD_NUMBER"))
        .map(s => try Option(s.toInt) catch { case _: NumberFormatException => Option.empty[Int] })
        .flatMap(ci => Version(ver).map(v => withoutSnapshot(v).copy(bugfix = ci).string))
        .orElse(Version(ver).map(v => withoutSnapshot(v).string))
        .getOrElse(versionFormatError)
    },
    releaseProcess := Seq(
      Seq[ReleaseStep](
        checkSnapshotDependencies,
        inquireVersions,
        runTest,
        setReleaseVersion,
        commitReleaseVersion,
        tagRelease,
        publishArtifacts,
        setNextVersion,
        commitNextVersion
      ),
      // only job *.1 pushes tags, to avoid each independent job attempting to retag the same release
      Option(System.getenv("TRAVIS_JOB_NUMBER")) filter { _ endsWith ".1" } map { _ =>
        pushChanges.copy(check = identity) } toSeq
    ).flatten
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
      sys.env.get("SCALAZ_STREAM_VERSION").getOrElse("0.7.3a")
    },
    unmanagedSourceDirectories in Compile += (sourceDirectory in Compile).value / s"scalaz-stream-${scalazStreamVersion.value.take(3)}",
    version := {
      val suffix = if(scalazStreamVersion.value.startsWith("0.7")) "" else "a"
      val versionValue = version.value
      if(versionValue.endsWith("-SNAPSHOT"))
        versionValue.replaceAll("-SNAPSHOT", s"$suffix-SNAPSHOT")
      else s"$versionValue$suffix"
    }
  )
}
