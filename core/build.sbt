
import oncue.build._

resolvers += "Scalaz Bintray Repo" at "http://dl.bintray.com/scalaz/releases"

libraryDependencies ++= Seq(
  "org.scalaz"     %% "scalaz-core"       % "7.0.5",
  "org.scalaz"     %% "scalaz-concurrent" % "7.0.5",
  "org.scalaz.stream" %% "scalaz-stream"  % "0.4.1",
  "scala-parsers"  %% "scala-parsers"     % "0.1"
)

OnCue.baseSettings

ScalaCheck.settings
