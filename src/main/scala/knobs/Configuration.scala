package knobs

import scalaz.concurrent.Task

/**
 * Global configuration data. This is the top-level config from which
 * `Config` values are derived by choosing a root location.
 */
case class BaseConfig(paths: Task[List[(Name, Worth[Path])]],
                      cfgMap: Task[Map[Name, CfgValue]])

/** Configuration data */
case class Config(root: String, baseConfig: BaseConfig)

