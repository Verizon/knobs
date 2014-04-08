
resolvers ++= Seq(
  "im.nexus" at "http://nexus.svc.oncue.com/nexus/content/groups/intel_media_maven/",
  "scct-github-repository" at "http://mtkopone.github.com/scct/maven-repo"
)

addSbtPlugin("intelmedia.build" %% "sbt-imbuild" % "5.1.+")

credentials += Credentials(Path.userHome / ".ivy2" / ".credentials")
