
resolvers ++= Seq(
  "im.nexus" at "http://nexus.inf.premeditated.tv/nexus/content/groups/intel_media_maven/",
  "scct-github-repository" at "http://mtkopone.github.com/scct/maven-repo"
)

addSbtPlugin("intelmedia.build" %% "sbt-imbuild" % "5.0.+")

addSbtPlugin("com.sqality.scct" % "sbt-scct" % "0.2.2")

credentials += Credentials(Path.userHome / ".ivy2" / ".credentials")
