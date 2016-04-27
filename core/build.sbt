
import common.scalazStreamVersion

common.settings

resolvers ++= Seq(
  "scalaz.bintray" at "http://dl.bintray.com/scalaz/releases",
  "oncue.bintray" at "http://dl.bintray.com/oncue/releases"
)

libraryDependencies ++= Seq(
  "org.scalaz.stream" %% "scalaz-stream" % scalazStreamVersion.value,
  "oncue.ermine"      %% "ermine-parser" % "0.3.0"
)
