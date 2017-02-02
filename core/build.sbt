val scalazVersion = sys.env.getOrElse("SCALAZ_VERSION", "7.2.8")
val scalazStreamVersion = sys.env.getOrElse("SCALAZ_STREAM_VERSION", "0.8.6a")
val scalazMajorVersion = scalazVersion.take(3)


libraryDependencies ++= {
  val ermineVersion =
    if(scalazStreamVersion.endsWith("a")) "0.3.5"
    else "0.3.5a"

  Seq(
    "org.scalaz.stream" %% "scalaz-stream" % scalazStreamVersion,
    "io.verizon.ermine" %% "parser" % ermineVersion
  )
}

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

