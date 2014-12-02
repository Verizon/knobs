
import oncue.build._

resolvers += "Scalaz Bintray Repo" at "http://dl.bintray.com/scalaz/releases"

libraryDependencies ++= Seq(
  "org.scalaz.stream" %% "scalaz-stream"     % "0.6a",
  "oncue.ermine"     %% "scala-parsers"     % "0.2.1-1"
)

OnCue.baseSettings

ScalaCheck.settings
