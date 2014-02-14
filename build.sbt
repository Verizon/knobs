name := "Knobs"

version := "0.1"

scalaVersion := "2.10.1"

scalacOptions += "-deprecation"

scalacOptions += "-feature"

resolvers ++= Seq(
  "releases"  at "http://oss.sonatype.org/content/repositories/releases",
  "tpolecat"  at "http://dl.bintray.com/tpolecat/maven"
)

libraryDependencies ++= Seq("org.scalaz" %% "scalaz-core" % "7.0.5",
                            "org.scalaz" %% "scalaz-concurrent" % "7.0.5")

libraryDependencies += "org.scalacheck" %% "scalacheck" % "1.10.1" % "test"

libraryDependencies += "com.comonad" %% "attoparsec" % "0.2"   

libraryDependencies ++= Seq(
  "org.spire-math" %% "spire"       % "0.6.0",
  "org.tpolecat"   %% "atto"        % "0.1"
)
