
import oncue.build._

libraryDependencies ++= Seq(
  "org.apache.curator" % "curator-framework" % "2.6.0",
  "org.apache.curator" % "curator-test" % "2.6.0" % "test"
)

OnCue.baseSettings

ScalaCheck.settings

licenses += ("Apache-2.0", url("https://www.apache.org/licenses/LICENSE-2.0.html"))
