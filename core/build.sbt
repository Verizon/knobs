import verizon.build.ScalazPlugin._

resolvers ++= Seq(
  "scalaz.bintray" at "http://dl.bintray.com/scalaz/releases",
  "oncue.bintray" at "http://dl.bintray.com/oncue/releases"
)

libraryDependencies ++= {
  Seq(
    "org.scalaz.stream" %% "scalaz-stream" % (scalazVersion.value match {
      case VersionNumber(Seq(7, 1, _*), _, _) => "0.7.3a"
      case VersionNumber(Seq(7, 2, _*), _, _) => "0.8.6a"
    }),
    "io.verizon.ermine" %% "parser" % scalazCrossVersioners.default(scalazVersion.value)("0.4.0-SNAPSHOT")
  )
}
