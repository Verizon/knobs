libraryDependencies ++= {
  val ermineVersion =
    if(scalazStreamVersion.value.startsWith("0.7")) "0.3.5a"
    else "0.3.5"

  Seq(
    "org.scalaz.stream" %% "scalaz-stream" % scalazStreamVersion.value,
    "io.verizon.ermine" %% "parser" % ermineVersion
  )
}
