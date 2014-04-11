
resolvers += "im.nexus" at "http://nexus.svc.oncue.com/nexus/content/groups/intel_media_maven/"

resolvers += "ermine.nexus" at "http://nexus.svc.oncue.com/nexus/content/repositories/bintray-ermine/"

credentials += Credentials(Path.userHome / ".ivy2" / ".credentials")
