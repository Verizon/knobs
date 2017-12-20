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

import cats._
import cats.effect.{Effect, Sync}
import cats.implicits._
import fs2.Stream
import fs2.async.signalOf

import scala.concurrent.ExecutionContext

/** Mutable, reloadable, configuration data */
case class MutableConfig[F[_]](root: String, base: BaseConfig[F]) {

  /**
   * Gives a `MutableConfig` corresponding to just a single group of the
   * original `MutableConfig`. The subconfig can be used just like the original.
   */
  def subconfig(g: Name): MutableConfig[F] =
    MutableConfig(root + g + (if (g.isEmpty) "" else "."), base)

  /**
   * Forcibly reload this `MutableConfig` from sources. Throws an exception on error,
   * such as * if files no longer exist or contain errors. If the provided `MutableConfig`
   * is a `subconfig`, this will reload the entire top-level configuration, not
   * just the local section. Any overridden properties set with `addProperties`
   * will disappear.
   */
  def reload(implicit F: Effect[F]): F[Unit] = base.reload

  /**
   * Add additional files to this `MutableConfig`, causing it to be reloaded to
   * add their contents.
   */
  def add(paths: List[KnobsResource])(implicit F: Effect[F]): F[Unit] =
    addGroups(paths.map(x => ("", x)))

  /**
   * Add additional files to named groups in this `MutableConfig`, causing it to be
   * reloaded to add their contents.
   */
  def addGroups(paths: List[(Name, KnobsResource)])(implicit F: Effect[F]): F[Unit] = {
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
  def addEnv(props: Env)(implicit F: Effect[F]): F[Unit] = for {
    p <- base.cfgMap.atomicModify { m =>
      val mp = m ++ props.map { case (k, v) => (root + k, v) }
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
  def addStrings(props: Map[Name, String])(implicit F: Effect[F]): F[Unit] = addMap(props)

  /**
   * Add the properties in the given `Map` to this config. The values
   * will be converted to `CfgValue`s according to their `Valuable` instance.
   * Note: If this config is reloaded from source, these additional properties
   * will be lost.
   */
  def addMap[V:Valuable](props: Map[Name, V])(implicit F: Effect[F]): F[Unit] =
    addEnv(props.toList.foldLeft(Map[Name, CfgValue]()) {
      case (m, (k, v)) => m + (k -> Valuable[V].convert(v))
    })

  /**
   * Look up a name in the `MutableConfig`. If a binding exists, and the value can
   * be converted to the desired type, return the converted value, otherwise
   * `None`.
   */
  def lookup[A:Configured](name: Name)(implicit F: Functor[F]): F[Option[A]] =
    base.cfgMap.read.map(_.get(root + name).flatMap(_.convertTo[A]))


  /**
   * Look up a name in the `MutableConfig`. If a binding exists, and the value can
   * be converted to the desired type, return the converted value, otherwise
   * return the default value.
   */
  def lookupDefault[A:Configured](default: A, name: Name)(implicit F: Functor[F]): F[A] =
    lookup(name).map(_.getOrElse(default))

  /**
   * Look up a name in the `MutableConfig`. If a binding exists, and the value can
   * be converted to the desired type, return the converted value, otherwise
   * throw a `KeyError`.
   */
  def require[A:Configured](name: Name)(implicit F: Monad[F]): F[A] = for {
    v <- lookup(name)
    r <- v.map(F.pure).getOrElse(
      getEnv.map(_.get(name).fold(throw KeyError(name))(v => throw ValueError(name, v)))
    )
  } yield r

  /**
   * Perform a simple dump of a `MutableConfig` to the console.
   */
  @deprecated("Use `pretty` instead", "2.2")
  def display(implicit F: Sync[F]): F[Unit] = pretty.flatMap(x => F.delay(println(x)))

  /**
   * Perform a simple dump of a `MutableConfig` to a `String`.
   */
  def pretty(implicit F: Functor[F]): F[String] =
    base.cfgMap.read.map(_.flatMap {
      case (k, v) if (k startsWith root) => Some(s"$k = ${v.pretty}")
      case _ => None
    }.mkString("\n"))

  /**
   * Fetch the `Map` that maps names to values. Turns the config into a pure
   * value disconnected from the file resources it came from.
   */
  def getEnv(implicit F: Functor[F]): F[Env] = immutable.map(_.env)

  /**
   * Get an immutable `Config` from of the current state of this
   * `MutableConfig`.
   */
  def immutable(implicit F: Functor[F]): F[Config] = base at root

  /**
   * Subscribe to notifications. The given handler will be invoked when any
   * change occurs to a configuration property that matches the pattern.
   */
  def subscribe(p: Pattern, h: ChangeHandler[F]): F[Unit] =
    base.subs.modify { map =>
      map.get(p.local(root)) match {
        case None           => map + ((p.local(root), List(h)))
        case Some(existing) => map + ((p.local(root), existing ++ List(h)))
      }
    }

  /**
   * A process that produces chages to the configuration properties that match
   * the given pattern
   */
  def changes(p: Pattern)(implicit F: Effect[F], ec: ExecutionContext): Stream[F, (Name, Option[CfgValue])] = {
    val signal = signalOf[F, (Name, Option[CfgValue])](("", None)) // TP: Not sure about the soundness of this default?

    Stream.eval {
      for {
        sig <- signal
        _   <- subscribe(p, (k, v) => sig.set((k, v)))
      } yield ()
    }.flatMap(_ => Stream.force(signal.map(_.discrete))).drop(1) // droping the previously initialized tuple of the signal.
  }
}
