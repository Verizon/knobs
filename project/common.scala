
import sbt._, Keys._
import bintray.BintrayKeys._

object common {
  val scalaTestVersion  = SettingKey[String]("scalatest version")
  val scalaCheckVersion = SettingKey[String]("scalacheck version")

  def testSettings = Seq(
    scalaTestVersion     := "2.2.5",
    scalaCheckVersion    := "1.12.3",
    libraryDependencies ++= Seq(
      "org.scalatest"  %% "scalatest"  % scalaTestVersion.value  % "test",
      "org.scalacheck" %% "scalacheck" % scalaCheckVersion.value % "test"
    )
  )

  def publishSettings = Seq(
    publishMavenStyle := true,
    scmInfo := Some(ScmInfo(url("https://github.com/oncue/knobs"),
                            "git@github.com:oncue/knobs.git")),
    bintrayPackageLabels := Seq("configuration", "functional programming", "scala", "reasonable"),
    bintrayOrganization := Some("oncue"),
    bintrayRepository := "releases",
    bintrayPackage := "knobs"
  )
}
