package knobs

import org.scalacheck._
import scalaz.concurrent.Task
import Prop._

object Test extends Properties("Knobs") {
  def withLoad[A](files: List[KnobsResource])(
    t: MutableConfig => Task[A]): Task[A] = for {
      mb <- load(files)
      r <- t(mb)
    } yield r

  lazy val loadTest: Task[Prop] =
    withLoad(List(Required(ClassPathResource("pathological.cfg")))) { cfg => for {
      aa <- cfg.lookup[Int]("aa")
      ab <- cfg.lookup[String]("ab")
      acx <- cfg.lookup[Int]("ac.x")
      acy <- cfg.lookup[Boolean]("ac.y")
      ad <- cfg.lookup[Boolean]("ad")
      ae <- cfg.lookup[Int]("ae")
      af <- cfg.lookup[(Int, Int)]("af")
      db <- cfg.lookup[Boolean]("ag.q-e.i_u9.a")
  } yield (aa == Some(1)) :| "int property" &&
          (ab == Some("foo")) :| "string property" &&
          (acx == Some(1)) :| "nested int" &&
          (acy == Some(true)) :| "nested bool" &&
          (ad == Some(false)) :| "simple bool" &&
          (ae == Some(1)) :| "simple int 2" &&
          (af == Some((2, 3))) :| "list property" &&
          (db == Some(false)) :| "deep bool" }

  lazy val interpTest: Task[Prop] =
    withLoad(List(Required(ClassPathResource("pathological.cfg")))) { cfg => for {
      home <- Task(sys.env.get("HOME"))
      cfgHome <- cfg.lookup[String]("ba")
    } yield cfgHome == home }

  lazy val importTest: Task[Prop] =
    withLoad(List(Required(ClassPathResource("import.cfg")))) { cfg => for {
      aa <- cfg.lookup[Int]("x.aa")
      p1 = (aa == Some(1)) :| "simple"
      acx <- cfg.lookup[Int]("x.ac.x")
      p2 = (acx == Some(1)) :| "nested"
    } yield p1 && p2 }

  lazy val loadPropertiesTest: Task[Prop] =
    withLoad(List(Required(SysPropsResource(Prefix("path"))))) { cfg =>
      cfg.lookup[String]("path.separator").map(_.isDefined)
    }

  lazy val fallbackTest: Task[Prop] =
    withLoad(List(Required(
      ClassPathResource("foobar.cfg") or
      ClassPathResource("pathological.cfg")))) { _.lookup[Int]("aa").map(_ == Some(1)) }

  property("load-pathological-config") = loadTest.run

  property("interpolation") = interpTest.run

  property("import") = importTest.run

  property("load-system-properties") = loadPropertiesTest.run

  property("load-fallback-chain") = fallbackTest.run

}
