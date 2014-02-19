package knobs

import scalaz.concurrent.Task
import scala.concurrent.duration.Duration
import scalaz.syntax.traverse._
import scalaz.syntax.std.map._
import scalaz.std.list._

/**
 * Global configuration data. This is the top-level config from which
 * `Config` values are derived by choosing a root location.
 */
case class BaseConfig(paths: IORef[List[(Name, Worth[Resource])]],
                      cfgMap: IORef[Map[Name, CfgValue]],
                      subs: IORef[Map[Pattern, List[ChangeHandler]]]) {

  /**
   * Get the `Config` at the given root location.
   */
  def at(root: String) = Config("", this).subconfig(root)

  lazy val reload: Task[Unit] = for {
    ps <- paths.read
    mp <- loadFiles(ps.map(_._2)).flatMap(flatten(ps, _))
    m  <- cfgMap.atomicModify(m => (mp, m))
    _ <- subs.read.flatMap(notifySubscribers(m, mp, _))
  } yield ()
}

/** Configuration data */
case class Config(root: String, base: BaseConfig) {

  /**
   * Gives a `Config` corresponding to just a single group of the original `Config`.
   * The subconfig can be used just like the original.
   */
  def subconfig(g: Name): Config =
    Config(root + g + (if (g.isEmpty) "" else "."), base)

  /**
   * Forcibly reload this `Config`. Throws an exception on error, such as
   * if files no longer exist or contain errors. If the provided `Config` is
   * a `subconfig`, this will reload the entire top-level configuration, not
   * just the local section.
   */
  lazy val reload: Task[Unit] = base.reload

  /**
   * Add additional files to this `Config`, causing it to be reloaded to
   * add their contents.
   */
  def add(paths: List[Worth[Resource]]): Task[Unit] =
    addGroups(paths.map(x => ("", x)))

  /**
   * Add additional files to named groups in this `Config`, causing it to be
   * reloaded to add their contents.
   */
  def addGroups(paths: List[(Name, Worth[Resource])]): Task[Unit] = {
    def fix[A](p: (String, A)) = (addDot(p._1), p._2)
    for {
      _ <- base.paths.modify(prev => prev ++ paths.map(fix))
      _ <- base.reload
    } yield ()
  }

  /**
   * Look up a name in the `Config`. If a binding exists, and the value can
   * be converted to the desired type, return the converted value, otherwise
   * `None`.
   */
  def lookup[A:Configured](name: Name): Task[Option[A]] =
    base.cfgMap.read.map(_.get(root + name).flatMap(_.convertTo[A]))


  /**
   * Look up a name in the `Config`. If a binding exists, and the value can
   * be converted to the desired type, return the converted value, otherwise
   * return the default value.
   */
  def lookupDefault[A:Configured](default: A, name: Name): Task[A] =
    lookup(name).map(_.getOrElse(default))

  /**
   * Look up a name in the `Config`. If a binding exists, and the value can
   * be converted to the desired type, return the converted value, otherwise
   * throw a `KeyError`.
   */
  def require[A:Configured](name: Name): Task[A] = for {
    v <- lookup(name)
    r <- v.map(Task(_)).getOrElse(Task.fail(KeyError(name)))
  } yield r

  /**
   * Perform a simple dump of a `Config` to the console.
   */
  def display: Task[Unit] =
    base.cfgMap.read.flatMap(_.toList.traverse_ {
      case (k, v) => Task(if (k startsWith root) println(s"$k = ${v.pretty}"))
    })

  /**
   * Fetch the `Map` that maps names to values. Turns the config into a pure value
   * disconnected from the file resources it came from.
   */
  def getMap: Task[Map[Name, CfgValue]] =
    base.cfgMap.read

  /**
   * Subscribe to notifications. The given handler will be invoked when any change
   * occurs to a configuration property that matches the pattern.
   */
  def subscribe(p: Pattern, h: ChangeHandler): Task[Unit] =
    base.subs.modify(_.insertWith(p local root, List(h))(_ ++ _))
}

