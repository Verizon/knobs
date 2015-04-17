//: ----------------------------------------------------------------------------
//: Copyright (C) 2015 Verizon.  All Rights Reserved.
//:
//:   Licensed under the Apache License, Version 2.0 (the "License");
//:   you may not use this file except in compliance with the License.
//:   You may obtain a copy of the License at
//:
//:       http://www.apache.org/licenses/LICENSE-2.0
//:
//:   Unless required by applicable law or agreed to in writing, software
//:   distributed under the License is distributed on an "AS IS" BASIS,
//:   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//:   See the License for the specific language governing permissions and
//:   limitations under the License.
//:
//: ----------------------------------------------------------------------------
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
  def apply(pattern: String): Pattern = pattern match {
    case p if p.endsWith(".*") => Prefix(p.substring(0, p.length - 2))
    case _ => Exact(pattern)
  }
}
