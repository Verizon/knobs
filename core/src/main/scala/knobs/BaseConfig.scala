package knobs

import scalaz.concurrent.Task

/**
 * Global configuration data. This is the top-level config from which
 * `Config` values are derived by choosing a root location.
 */
case class BaseConfig(paths: IORef[List[(Name, Worth[Resource])]],
                      cfgMap: IORef[Env],
                      subs: IORef[Map[Pattern, List[ChangeHandler]]]) {

  /**
   * Get the `MutableConfig` at the given root location.
   */
  def mutableAt(root: String): MutableConfig =
    MutableConfig("", this).subconfig(root)

  /**
   * Get the `Config` at the given root location
   */
  def at(root: String): Task[Config] =
    cfgMap.read.map(Config(_).subconfig(root))

  lazy val reload: Task[Unit] = for {
    ps <- paths.read
    mp <- loadFiles(ps.map(_._2)).flatMap(flatten(ps, _))
    m  <- cfgMap.atomicModify(m => (mp, m))
    s  <- subs.read
    _ <- notifySubscribers(m, mp, s)
  } yield ()
}


