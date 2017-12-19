resolvers ++= Seq(
  "oncue.bintray" at "http://dl.bintray.com/oncue/releases"
)

libraryDependencies ++= {
  Seq(
    "co.fs2"            %% "fs2-core" % "0.10.0-M8",
    "io.verizon.ermine" %% "parser"   % "0.5.0-SNAPSHOT"
  )
}
