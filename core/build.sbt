import verizon.build.ScalazPlugin._

resolvers ++= Seq(
  "scalaz.bintray" at "http://dl.bintray.com/scalaz/releases",
  "oncue.bintray" at "http://dl.bintray.com/oncue/releases"
)

libraryDependencies ++= {
  Seq(
    "org.scalaz.stream" %% "scalaz-stream" % scalazCrossVersioners.`scalaz-stream-0.8`(scalazVersion.value)("0.8.6"),
    "io.verizon.ermine" %% "parser" % scalazCrossVersioners.default(scalazVersion.value)("0.4.7")
  )
}
