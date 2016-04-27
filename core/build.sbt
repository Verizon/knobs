
import common.scalazStreamVersion

common.settings

resolvers ++= Seq(
  "scalaz.bintray" at "http://dl.bintray.com/scalaz/releases",
  "oncue.bintray" at "http://dl.bintray.com/oncue/releases"
)

libraryDependencies ++= {
  val ermineVersion =
    if(scalazStreamVersion.value.startsWith("0.7")) "0.2.1-2"
    else "0.3.0"

  Seq(
    "org.scalaz.stream" %% "scalaz-stream" % scalazStreamVersion.value,
    "oncue.ermine"      %% "ermine-parser" % ermineVersion
  )
}
