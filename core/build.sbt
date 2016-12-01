
resolvers ++= Seq(
  "scalaz.bintray" at "http://dl.bintray.com/scalaz/releases",
  "oncue.bintray" at "http://dl.bintray.com/oncue/releases"
)

libraryDependencies ++= {
  val ermineVersion =
    if(scalazStreamVersion.value.endsWith("a")) "0.3.3a"
    else "0.3.3"

  Seq(
    "org.scalaz.stream" %% "scalaz-stream" % scalazStreamVersion.value,
    "io.verizon.ermine" %% "parser" % ermineVersion
  )
}
