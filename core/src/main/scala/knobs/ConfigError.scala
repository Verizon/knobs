package knobs

import scalaz.syntax.show._

/** An error during parsing of a configuration file */
case class ConfigError[R:Resource](path: R, err: String) extends Exception(s"${path.show}:$err")

