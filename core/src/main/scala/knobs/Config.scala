package knobs

import scalaz.syntax.std.map._
import scalaz.\/
import scalaz.\/._

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

object Config {
  val empty = Config(Map())

  def parse(s: String): String \/ Config = {
    import ConfigParser._
    def go(pfx: String, acc: Env, ds: List[Directive]): Env =
      ds.foldLeft(acc)((m, d) => d match {
        case Bind(name, v) => m + ((pfx + name) -> v)
        case Group(name, gds) => go(pfx + name + ".", m, gds)
        case x => sys.error("Unexpected directive: $x")
      })
    runParser(sansImport, s) match {
      case Left(e) => left(e.message.toString)
      case Right((_, ds)) => right(Config(go("", empty.env, ds)))
    }
  }
}
