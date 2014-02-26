package knobs

import scalaz.concurrent.Task
import scala.concurrent.duration.Duration
import scalaz.syntax.traverse._
import scalaz.syntax.std.map._
import scalaz.std.list._

/** Mutable configuration data */
case class MutableConfig(root: String, base: BaseConfig) {

  /**
   * Gives a `MutableConfig` corresponding to just a single group of the original `MutableConfig`.
   * The subconfig can be used just like the original.
   */
  def subconfig(g: Name): MutableConfig =
    MutableConfig(root + g + (if (g.isEmpty) "" else "."), base)

  /**
   * Forcibly reload this `MutableConfig` from sources. Throws an exception on error,
   * such as * if files no longer exist or contain errors. If the provided `MutableConfig`
   * is a `subconfig`, this will reload the entire top-level configuration, not
   * just the local section. Any overridden properties set with `addProperties`
   * will disappear.
   */
  lazy val reload: Task[Unit] = base.reload

  /**
   * Add additional files to this `MutableConfig`, causing it to be reloaded to
   * add their contents.
   */
  def add(paths: List[Worth[Resource]]): Task[Unit] =
    addGroups(paths.map(x => ("", x)))

  /**
   * Add additional files to named groups in this `MutableConfig`, causing it to be
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
   * Add the properties in the given pure environment to this config.
   * Note: If this config is reloaded from source, these additional properties
   * will be lost.
   */
  def addEnv(props: Env): Task[Unit] = for {
    p <- base.cfgMap.atomicModify { m =>
      val mp = m ++ props
      (mp, (m, mp))
    }
    (m, mp) = p
    subs <- base.subs.read
    _ <- notifySubscribers(m, mp, subs)
  } yield ()

  /**
   * Add the properties in the given `Map` to this config. The `String` values
   * will be parsed into `CfgValue`s.
   * Note: If this config is reloaded from source, these additional properties
   * will be lost.
   */
  def addStrings(props: Map[Name, String]): Task[Unit] =
    addEnv(props.toList.foldLeft(Map[Name, CfgValue]()) {
      case (m, (k, v)) =>
        import ConfigParser._
        value.parse(v).fold(
          e => m + (k -> CfgText(v)),
          r => m + (k -> r)
        )
    })

  /**
   * Look up a name in the `MutableConfig`. If a binding exists, and the value can
   * be converted to the desired type, return the converted value, otherwise
   * `None`.
   */
  def lookup[A:Configured](name: Name): Task[Option[A]] =
    base.cfgMap.read.map(_.get(root + name).flatMap(_.convertTo[A]))


  /**
   * Look up a name in the `MutableConfig`. If a binding exists, and the value can
   * be converted to the desired type, return the converted value, otherwise
   * return the default value.
   */
  def lookupDefault[A:Configured](default: A, name: Name): Task[A] =
    lookup(name).map(_.getOrElse(default))

  /**
   * Look up a name in the `MutableConfig`. If a binding exists, and the value can
   * be converted to the desired type, return the converted value, otherwise
   * throw a `KeyError`.
   */
  def require[A:Configured](name: Name): Task[A] = for {
    v <- lookup(name)
    r <- v.map(Task(_)).getOrElse(Task.fail(KeyError(name)))
  } yield r

  /**
   * Perform a simple dump of a `MutableConfig` to the console.
   */
  def display: Task[Unit] =
    base.cfgMap.read.flatMap(_.toList.traverse_ {
      case (k, v) => Task(if (k startsWith root) println(s"$k = ${v.pretty}"))
    })

  /**
   * Fetch the `Map` that maps names to values. Turns the config into a pure value
   * disconnected from the file resources it came from.
   */
  def getEnv: Task[Env] =
    base.cfgMap.read

  /**
   * Get an immutable `Config` from of the current state of this `MutableConfig`.
   */
  def immutable: Task[Config] = base at root

  /**
   * Subscribe to notifications. The given handler will be invoked when any change
   * occurs to a configuration property that matches the pattern.
   */
  def subscribe(p: Pattern, h: ChangeHandler): Task[Unit] =
    base.subs.modify(_.insertWith(p local root, List(h))(_ ++ _))
}

