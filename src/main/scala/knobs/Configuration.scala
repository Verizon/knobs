package knobs

import scalaz.concurrent.Task

/**
 * Global configuration data. This is the top-level config from which
 * `Config` values are derived by choosing a root location.
 */
case class BaseConfig(paths: List[(Name, Worth[Path])],
                      cfgMap: Map[Name, CfgValue])

/** Configuration data */
case class Config(root: String, baseConfig: BaseConfig) {

  /**
   * Gives a `Config` corresponding to just a single group of the original `Config`.
   * The subconfig can be used just like the original.
   */
  def subconfig(g: Name): Config =
    Config(root + g + ".", baseConfig)

  /**
   * Look up a name in the given `Config`. If a binding exists, and the value can
   * be converted to the desired type, return the converted value, otherwise
   * `None`.
   */
  def lookup[A:Configured](name: Name): Option[A] =
    baseConfig.cfgMap.get(root + name).flatMap(_.convertTo[A])


  /**
   * Look up a name in the given `Config`. If a binding exists, and the value can
   * be converted to the desired type, return the converted value, otherwise
   * return the default value.
   */
  def lookupDefault[A:Configured](default: A, name: Name): A =
    lookup(name).getOrElse(default)

  /**
   * Perform a simple dump of a `Config` to the console.
   */
  def display(cfg: Config): Task[Unit] =
    Task { print(root -> cfg.baseConfig.cfgMap) }

  /**
   * Fetch the `Map` that maps names to values.
   */
  def getMap: Map[Name, CfgValue] =
    baseConfig.cfgMap

}

