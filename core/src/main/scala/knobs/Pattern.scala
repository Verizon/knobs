package knobs

/**
  * A pattern that matches a `Name` either exactly or as a prefix.
  */
sealed trait Pattern {
  final def local(pfx: String) = this match {
    case Exact(s) => Exact(pfx ++ s)
    case Prefix(s) => Prefix(pfx ++ s)
  }
  final def matches(n: Name): Boolean = this match {
    case Exact(s) => s == n
    case Prefix(s) => n startsWith n
  }
}

/** An exact match */
case class Exact(name: Name) extends Pattern

/**
 * A prefix match. Given `Prefix("foo")`, this will match
 * `"foo.bar"`, but not `"foo"` or `"foobar"`.
 */
case class Prefix(name: Name) extends Pattern

object Pattern {
  /**
   * Turn a string into a pattern. A string ending in `".*"` is a `Prefix` pattern.
   * Any other string is assumed to be `Exact`.
   */
  def apply(p: String) = {
    if (p.endsWith(".*")) Prefix(p.substring(0, p.length - 2))
  }
}
