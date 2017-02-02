
libraryDependencies ++= Seq(
  "org.apache.curator" % "curator-framework" % "2.8.0",
  "org.apache.curator" % "curator-test" % "2.8.0" % "test"
)

val scalazVersion = sys.env.getOrElse("SCALAZ_VERSION", "7.2.8")
val scalazMajorVersion = scalazVersion.take(3)

// Add scalaz version to project version
version := {
  version.value match {
    case v if v.endsWith("-SNAPSHOT") =>
      val replacement = s"-scalaz-$scalazMajorVersion-SNAPSHOT"
      v.replaceAll("-SNAPSHOT", replacement)
    case v =>
      s"${v}-scalaz-$scalazMajorVersion"
  }
}

unmanagedSourceDirectories in Compile +=
  (sourceDirectory in Compile).value /
    (s"scala-scalaz-$scalazMajorVersion.x")

