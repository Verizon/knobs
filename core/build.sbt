
import oncue.build._

resolvers += "Scalaz Bintray Repo" at "http://dl.bintray.com/scalaz/releases"

libraryDependencies ++= Seq(
  "org.scalaz.stream" %% "scalaz-stream"     % "0.5",
  "scala-parsers"     %% "scala-parsers"     % "0.1"
)

OnCue.baseSettings

ScalaCheck.settings
