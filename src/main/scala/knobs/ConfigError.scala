package knobs

/** An error during parsing of a configuration file */
case class ConfigError(path: FilePath, err: String) extends Throwable

