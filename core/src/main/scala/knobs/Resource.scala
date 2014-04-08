package knobs

import java.net.URL
import java.io.File

/** Resources from which configuration files can be loaded */
sealed trait Resource {
  /**
    * Returns a resource that has the given path resolved against this `Resource`.
    * If the given path is absolute (not relative), a new resource to that path is returned.
    * Otherwise the given path is appended to this resource's path.
    * For example, if this resource is the URI "http://tempuri.org/foo/", and the given
    * path is "bar/baz", then the resulting resource will be the URI "http://tempuri.org/foo/bar/baz"
    */
  def resolve(child: String): Resource = {
    def res(p: String, c: String) =
      if (c.head == '/') c
      else p.substring(0, p.lastIndexOf('/') + 1) ++ c

    this match {
      case URLResource(p) => URLResource(new URL(p, child))
      case FileResource(p) => FileResource(new File(res(p.getPath, child)))
      case ClassPathResource(p) => ClassPathResource(res(p, child))
      case SysPropsResource(p) => SysPropsResource(p match {
        case Exact(s) => Exact(s"$p.$s")
        case Prefix(s) => Prefix(s"$p.$s")
      })
    }
  }

  def show: String =
    this match {
      case URLResource(u) => u.toString
      case FileResource(f) => f.toString
      case SysPropsResource(p) => s"System properties $p.*"
      case ClassPathResource(r) => getClass.getClassLoader.getResource(r).toURI.toString
    }
}

case class FileResource(f: File) extends Resource
case class URLResource(u: URL) extends Resource
case class ClassPathResource(name: String) extends Resource
case class SysPropsResource(pattern: Pattern) extends Resource

object Resource {
  def apply(f: File): Resource = FileResource(f)
  def apply(u: URL): Resource = URLResource(u)
  def apply(c: String): Resource = ClassPathResource(c)
}

