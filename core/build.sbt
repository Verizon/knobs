
import oncue.build._

resolvers += "Scalaz Bintray Repo" at "http://dl.bintray.com/scalaz/releases"

libraryDependencies ++= Seq(
  "org.scalaz.stream" %% "scalaz-stream" % "0.7a",
  "oncue.ermine"      %% "scala-parsers" % "0.2.1-2"
)

licenses += ("Apache-2.0", url("https://www.apache.org/licenses/LICENSE-2.0.html"))

OnCue.baseSettings

ScalaCheck.settings
