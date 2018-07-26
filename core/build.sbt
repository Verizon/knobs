resolvers ++= Seq(
  "oncue.bintray" at "http://dl.bintray.com/oncue/releases"
)

libraryDependencies ++= {
  Seq(
    "co.fs2"            %% "fs2-core" % "1.0.0-M1",
    "io.verizon.ermine" %% "parser"   % "0.5.8"
  )
}
