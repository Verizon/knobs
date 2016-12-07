package verizon.build

import sbt._, Keys._

object CrossLibraryPlugin extends AutoPlugin {

  object autoImport {
    val scalazStreamVersion = settingKey[String]("scalaz-stream version")
  }

  import autoImport._

  override def trigger = allRequirements

  override def requires = RigPlugin

  override lazy val projectSettings = Seq(
    // "0.8.1a" "0.7.3a"
    scalazStreamVersion := {
      sys.env.get("SCALAZ_STREAM_VERSION").getOrElse("0.8.6a")
    },
    unmanagedSourceDirectories in Compile += (sourceDirectory in Compile).value / s"scalaz-stream-${scalazStreamVersion.value.take(3)}",
    version := {
      val suffix = if(List("0.7", "0.8").find(prefix => scalazStreamVersion.value.startsWith(prefix)).isDefined) "" else "a"
      val versionValue = version.value
      if(versionValue.endsWith("-SNAPSHOT"))
        versionValue.replaceAll("-SNAPSHOT", s"$suffix-SNAPSHOT")
      else s"$versionValue$suffix"
    }
  )
}
