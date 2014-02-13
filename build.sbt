name := "Knobs"

version := "0.1"

scalaVersion := "2.10.1"

scalacOptions += "-deprecation"

scalacOptions += "-feature"

resolvers += "Sonatype snapshots" at "http://oss.sonatype.org/content/repositories/snapshots/" 

resolvers += "spray repo" at "http://repo.spray.io"

libraryDependencies ++= Seq("org.scalaz" %% "scalaz-core" % "7.0.5",
                            "org.scalaz" %% "scalaz-concurrent" % "7.0.5")

libraryDependencies += "org.scalacheck" %% "scalacheck" % "1.10.1" % "test"

libraryDependencies += "com.comonad" %% "attoparsec" % "0.2"   
