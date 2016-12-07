resolvers ++= Seq(
  "scalaz.bintray" at "http://dl.bintray.com/scalaz/releases",
  "oncue.bintray" at "http://dl.bintray.com/oncue/releases"
)

libraryDependencies ++= {
  val ermineVersion =
    if(List("0.7", "0.8").find(prefix => scalazStreamVersion.value.startsWith(prefix)).isDefined) "0.3.4a"
    else "0.3.4"

  Seq(
    "org.scalaz.stream" %% "scalaz-stream" % scalazStreamVersion.value,
    "io.verizon.ermine" %% "parser" % ermineVersion
  )
}
