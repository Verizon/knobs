
// 1.3.0 is out but it depends on java 1.8.
libraryDependencies += "com.typesafe" % "config" % "1.2.1"

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

