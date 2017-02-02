import com.typesafe.sbt.SbtSite.SiteKeys._
import com.typesafe.sbt.SbtGhPages.GhPagesKeys._

enablePlugins(DisablePublishingPlugin)

site.settings

tutSettings

site.addMappingsToSiteDir(tut, "")

ghpages.settings

ghpagesNoJekyll := false

includeFilter in makeSite := "*.yml" | "*.md" | "*.html" | "*.css" | "*.png" | "*.jpg" | "*.gif" | "*.js" | "*.swf"

git.remoteRepo := "git@github.com:Verizon/knobs.git"

val scalazVersion = sys.env.getOrElse("SCALAZ_VERSION", "7.2.8")
val scalazMajorVersion = scalazVersion.take(3)

// Add scalaz version to project version
version := {
  version.value match {
    case v if v.endsWith("-SNAPSHOT") =>
      val replacement = s"-scalaz-$scalazMajorVersion-SNAPSHOT"
      v.replaceAll("-SNAPSHOT", replacement)
    case v =>
      s"${v}-scalaz-$scalazMajorVersion"
  }
}

unmanagedSourceDirectories in Compile +=
  (sourceDirectory in Compile).value /
    (s"scala-scalaz-$scalazMajorVersion.x")
