package knobs

/** An error during parsing of a configuration file */
case class ConfigError(path: Path, err: String) extends Throwable

