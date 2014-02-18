package knobs

import org.scalacheck._
import scalaz.concurrent.Task
import Prop._

object Test extends Properties("Knobs") {
  def withLoad[A](files: List[Worth[Resource]])(t: Config => Task[A]): Task[A] =
    for {
      mb <- load(files)
      r <- t(mb)
    } yield r

  lazy val loadTest: Task[Prop] =
    withLoad(List(Required(ClassPathResource("pathological.cfg")))) { cfg => Task {
      (cfg.lookup[Int]("aa") == Some(1)) :| "int property" &&
      (cfg.lookup[String]("ab") == Some("foo")) :| "string property" &&
      (cfg.lookup[Int]("ac.x") == Some(1)) :| "nested int" &&
      (cfg.lookup[Boolean]("ac.y") == Some(true)) :| "nested bool" &&
      (cfg.lookup[Boolean]("ad") == Some(false)) :| "simple bool" &&
      (cfg.lookup[Int]("ae") == Some(1)) :| "simple int 2" &&
      (cfg.lookup[(Int, Int)]("af") == Some((2, 3))) :| "list property" &&
      (cfg.lookup[Boolean]("ag.q-e.i_u9.a") == Some(false)) :| "deep bool"
    }}

  lazy val interpTest: Task[Prop] =
    withLoad(List(Required(ClassPathResource("pathological.cfg")))) { cfg => Task {
      val home = sys.env.get("HOME")
      val cfgHome = cfg.lookup[String]("ba")
      cfgHome == home
    }}

  lazy val importTest: Task[Prop] =
    withLoad(List(Required(ClassPathResource("import.cfg")))) { cfg => Task {
      { val aa = cfg.lookup[Int]("x.aa")
        aa == Some(1)
      } :| "simple" && {
        val acx = cfg.lookup[Int]("x.ac.x")
        acx == Some(1)
      } :| "nested"
    }}

  lazy val loadPropertiesTest: Task[Prop] =
    loadSystemProperties.map(_.lookup[String]("path.separator").isDefined)

  property("load-pathological-config") = loadTest.run

  property("interpolation") = interpTest.run

  property("import") = importTest.run

  property("load-system-properties") = loadPropertiesTest.run
}
