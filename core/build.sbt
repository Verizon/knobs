
common.settings

resolvers ++= Seq(
  "scalaz.bintray" at "http://dl.bintray.com/scalaz/releases",
  "oncue.bintray" at "http://dl.bintray.com/oncue/releases"
)

libraryDependencies ++= Seq(
  "org.scalaz.stream" %% "scalaz-stream" % "0.8.1a",
  "oncue.ermine"      %% "ermine-parser" % "0.3"
)
