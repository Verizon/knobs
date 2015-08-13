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

import org.scalacheck._
import scalaz.concurrent.Task
import Prop._
import scala.concurrent.duration._

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
      du <- cfg.lookup[Duration]("dur")
  } yield (aa == Some(1)) :| "int property" &&
          (ab == Some("foo")) :| "string property" &&
          (acx == Some(1)) :| "nested int" &&
          (acy == Some(true)) :| "nested bool" &&
          (ad == Some(false)) :| "simple bool" &&
          (ae == Some(1)) :| "simple int 2" &&
          (af == Some((2, 3))) :| "list property" &&
          (db == Some(false)) :| "deep bool" &&
          (du == Some(5.seconds)) :| "duration property"
  }

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

  // Check that there is one error per resource in the chain, plus one
  lazy val fallbackErrorTest: Task[Prop] =
    load(List(Required(ClassPathResource("foobar.cfg") or
                       ClassPathResource("foobar.cfg")))).attempt.map(_.fold(
      e =>
        { println(e);
        e.getMessage.toList.filter(_ == '\n').size == 3},
      a => false
    ))

  // Make sure that loading from a fake (but valid) URI fails with an expected error
  lazy val uriTest: Task[Prop] = {
    import java.net._
    load(List(Required(
      URIResource(new URI("http://lolcathost"))))).attempt.map(_.fold(
        {
          case e: UnknownHostException => true
          case _ => false
        },
        _ => false
      ))
  }

  // Ensure that the resource is *not* available on a new classloader
  lazy val classLoaderTest: Task[Prop] =
    load(List(Required(ClassPathResource("pathological.cfg", new java.net.URLClassLoader(Array.empty))))).attempt.map {
      case scalaz.-\/(f: java.io.FileNotFoundException) => true
      case _ => false
    }

  property("load-pathological-config") = loadTest.run

  property("interpolation") = interpTest.run

  property("import") = importTest.run

  property("load-system-properties") = loadPropertiesTest.run

  property("load-fallback-chain") = fallbackTest.run

  property("fallback-chain-errors") = fallbackErrorTest.run

  property("load-uri") = uriTest.run

  property("classloader") = classLoaderTest.run


}
