package knobs

/** An error during parsing of a configuration file */
case class ConfigError(path: Resource, err: String) extends Exception(s"$path:$err")

