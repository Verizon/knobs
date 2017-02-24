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
import compatibility._
import scalaz.\/

object Test extends Properties("Knobs") {

  // TODO remove when available in Scalacheck
  // https://github.com/rickynils/scalacheck/pull/284
  private implicit val arbFiniteDuration: Arbitrary[FiniteDuration] = Arbitrary(
    Gen.chooseNum(Long.MinValue + 1, Long.MaxValue).map(Duration.fromNanos))

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
      fdu <- cfg.lookup[FiniteDuration]("dur")
  } yield (aa == Some(1)) :| "int property" &&
          (ab == Some("foo")) :| "string property" &&
          (acx == Some(1)) :| "nested int" &&
          (acy == Some(true)) :| "nested bool" &&
          (ad == Some(false)) :| "simple bool" &&
          (ae == Some(1)) :| "simple int 2" &&
          (af == Some((2, 3))) :| "list property" &&
          (db == Some(false)) :| "deep bool" &&
          (du == Some(5.seconds)) :| "duration property" &&
          (fdu == Some(5.seconds)) :| "finite duration property"

  }

  lazy val interpTest: Task[Prop] =
    withLoad(List(Required(ClassPathResource("pathological.cfg")))) { cfg => for {
      home <- Task.delay(sys.env.get("HOME"))
      cfgHome <- cfg.lookup[String]("ba")
    } yield cfgHome == home }

  lazy val importTest: Task[Prop] =
    withLoad(List(Required(ClassPathResource("import.cfg")))) { cfg => for {
      aa <- cfg.lookup[Int]("x.aa")
      p1 = (aa == Some(1)) :| "simple"
      acx <- cfg.lookup[Int]("x.ac.x")
      p2 = (acx == Some(1)) :| "nested"
    } yield p1 && p2 }

  lazy val importAsIdentTest: Task[Prop] =
    load(List(Required(ClassPathResource("import-as-ident.cfg")))).attempt.map(_.fold(
      {
        case ConfigError(_, msg) => msg contains "reserved word (import) used as an identifier"
        case _ => false
      },
      _ => false
    ))

  lazy val loadPropertiesTest: Task[Prop] =
    withLoad(List(Required(SysPropsResource(Prefix("path"))))) { cfg =>
      cfg.lookup[String]("path.separator").map(_.isDefined)
    }

  lazy val propertiesSubconfigTest: Task[Prop] =
    withLoad(List(Required(SysPropsResource(Prefix("user"))))) { cfg =>
      cfg.subconfig("user").lookup[String]("name").map(_.isDefined)
    }

  lazy val propertiesNegativeTest: Task[Prop] =
    withLoad(List(Required(SysPropsResource(Prefix("user"))))) { cfg =>
      cfg.lookup[String]("path.separator").map(_.isEmpty)
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

  lazy val immutableConfigValueErrorTest: Task[Prop] = {
    sys.props += ("test.immutable.value.error" -> "12345")
    withLoad(List(Required(SysPropsResource(Prefix("test.immutable"))))) {
      mcfg => mcfg.immutable.map(
        cfg => \/.fromTryCatchNonFatal(cfg.require[String]("test.immutable.value.error")).fold(
          {
            case ValueError(n, v) => true
            case _ => false
          },
          _ => false
        )
      )
    }
  }

  lazy val mutableConfigValueErrorTest: Task[Prop] = {
    sys.props += ("test.mutable.value.error" -> "12345")
    withLoad(List(Required(SysPropsResource(Prefix("test.mutable"))))) { cfg =>
      cfg.require[String]("test.mutable.value.error").attempt.map(
        _.fold(
          {
            case ValueError(n, v) => true
            case _ => false
          },
          _ => false
        )
      )
    }
  }

  lazy val allCommentsTest: Task[Prop] =
    load(List(Required(ClassPathResource("all-comments.cfg")))).attempt.map(_.isRight)

  property("load-pathological-config") = loadTest.unsafePerformSync

  property("interpolation") = interpTest.unsafePerformSync

  property("import") = importTest.unsafePerformSync

  property("import-as-ident") = importAsIdentTest.unsafePerformSync

  property("load-system-properties") = loadPropertiesTest.unsafePerformSync

  property("system-properties-negative") = propertiesNegativeTest.unsafePerformSync

  property("system-properties-subconfig") = propertiesSubconfigTest.unsafePerformSync

  property("load-fallback-chain") = fallbackTest.unsafePerformSync

  property("fallback-chain-errors") = fallbackErrorTest.unsafePerformSync

  property("load-uri") = uriTest.unsafePerformSync

  property("classloader") = classLoaderTest.unsafePerformSync

  property("inifinite duration as finite") = {
    val g = Gen.oneOf(Duration.Inf, Duration.MinusInf, Duration.Undefined)
    forAll(g){ d =>
      Configured[FiniteDuration].apply(CfgDuration(d)) == None
    }
  }

  property("finite duration matches duration") = forAll { d: FiniteDuration =>
    val cfg = CfgDuration(d)
    (Configured[FiniteDuration].apply(cfg): Option[Duration]) ?= Configured[Duration].apply(cfg)
  }

  property("immutable-config-value-error") = immutableConfigValueErrorTest.unsafePerformSync

  property("mutable-config-value-error") = mutableConfigValueErrorTest.unsafePerformSync

  property("all-comments") = allCommentsTest.unsafePerformSync
}
