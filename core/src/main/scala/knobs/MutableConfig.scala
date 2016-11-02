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

import scalaz.concurrent.Task
import scala.concurrent.duration.Duration
import scalaz.syntax.traverse._
import scalaz.syntax.std.map._
import scalaz.std.list._

/** Mutable, reloadable, configuration data */
case class MutableConfig(root: String, base: BaseConfig) {

  /**
   * Gives a `MutableConfig` corresponding to just a single group of the
   * original `MutableConfig`. The subconfig can be used just like the original.
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
  def add(paths: List[KnobsResource]): Task[Unit] =
    addGroups(paths.map(x => ("", x)))

  /**
   * Add additional files to named groups in this `MutableConfig`, causing it to be
   * reloaded to add their contents.
   */
  def addGroups(paths: List[(Name, KnobsResource)]): Task[Unit] = {
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
      val mp = m ++ props.mapKeys(root + _)
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
  def addStrings(props: Map[Name, String]): Task[Unit] = addMap(props)

  /**
   * Add the properties in the given `Map` to this config. The values
   * will be converted to `CfgValue`s according to their `Valuable` instance.
   * Note: If this config is reloaded from source, these additional properties
   * will be lost.
   */
  def addMap[V:Valuable](props: Map[Name, V]): Task[Unit] =
    addEnv(props.toList.foldLeft(Map[Name, CfgValue]()) {
      case (m, (k, v)) => m + (k -> Valuable[V].convert(v))
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
    r <- v.map(Task.now).getOrElse(
      getEnv.map(_.get(name).fold(throw KeyError(name))(v => throw ValueError(name, v)))
    )
  } yield r

  /**
   * Perform a simple dump of a `MutableConfig` to the console.
   */
  @deprecated("Use `pretty` instead", "2.2")
  def display: Task[Unit] = pretty.flatMap(x => Task.now(println(x)))

  /**
   * Perform a simple dump of a `MutableConfig` to a `String`.
   */
  def pretty: Task[String] =
    base.cfgMap.read.map(_.flatMap {
      case (k, v) if (k startsWith root) => Some(s"$k = ${v.pretty}")
      case _ => None
    }.mkString("\n"))

  /**
   * Fetch the `Map` that maps names to values. Turns the config into a pure
   * value disconnected from the file resources it came from.
   */
  def getEnv: Task[Env] = immutable.map(_.env)

  /**
   * Get an immutable `Config` from of the current state of this
   * `MutableConfig`.
   */
  def immutable: Task[Config] = base at root

  /**
   * Subscribe to notifications. The given handler will be invoked when any
   * change occurs to a configuration property that matches the pattern.
   */
  def subscribe(p: Pattern, h: ChangeHandler): Task[Unit] =
    base.subs.modify(_.insertWith(p local root, List(h))(_ ++ _))

  import scalaz.stream.Process

  /**
   * A process that produces chages to the configuration properties that match
   * the given pattern
   */
  def changes(p: Pattern): Process[Task, (Name, Option[CfgValue])] = {
    import scalaz.stream.async.signalOf
    val sig = signalOf[(Name, Option[CfgValue])](("",None)) // TP: Not sure about the soundness of this default?
    Process.eval(subscribe(p, (k, v) => sig.set((k, v)))).flatMap(_ => sig.discrete).drop(1) // droping the previously initilized tuple of the signal.
  }
}

