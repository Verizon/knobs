package knobs

import scalaz.syntax.std.map._

/** Immutable configuration data */
case class Config(env: Env) {
  def subconfig(g: Name): Config = {
    val pfx = g + (if (g.isEmpty) "" else ".")
    Config(env.filterKeys(_ startsWith pfx).mapKeys(_.substring(pfx.length)))
  }

  def ++(cfg: Config): Config =
    Config(env ++ cfg.env)

  def lookup[A:Configured](name: Name): Option[A] =
    env.get(name).flatMap(_.convertTo[A])
}

