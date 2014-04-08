
import oncue.build._

libraryDependencies ++= Seq(
  "org.scalaz"     %% "scalaz-core"       % "7.0.5",
  "org.scalaz"     %% "scalaz-concurrent" % "7.0.5",
  "scala-parsers"  %% "scala-parsers"     % "0.1",
  "org.spire-math" %% "spire"             % "0.6.0"
)

OnCue.baseSettings

ScalaCheck.settings
