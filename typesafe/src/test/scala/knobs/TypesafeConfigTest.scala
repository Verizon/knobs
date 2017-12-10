//: ----------------------------------------------------------------------------
//: Copyright (C) 2016 Verizon.  All Rights Reserved.
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

import com.typesafe.config.ConfigFactory
import org.scalacheck.Properties

object TypesafeConfigTest extends Properties("Typesafe") {

  property("load-default-config") = {
    Typesafe.config.map { cfg =>
      cfg.lookup[Int]("foo.bar.an_int") == Some(1) &&
      cfg.lookup[String]("foo.bar.a_str") == Some("str") &&
      cfg.lookup[Boolean]("foo.bar.a_bool") == Some(true) &&
      cfg.lookup[List[Int]]("foo.an_int_list") == Some(List(1,2,3)) &&
      cfg.lookup[List[String]]("foo.a_str_list") == Some(List("a","b","c"))
    }
  }.unsafeRunSync

  property("load-custom-config") = {
    val customCfg = ConfigFactory.parseString(
      """
        |baz = {
        |  qux = {
        |    an_int = 2
        |    a_str = rts
        |    a_bool = false
        |  }
        |  an_int_list = [4, 5, 6]
        |  a_str_list = [d, e, f]
        |}
      """.stripMargin)

      Typesafe.config(customCfg).map { cfg =>
        cfg.lookup[Int]("baz.qux.an_int") == Some(2) &&
        cfg.lookup[String]("baz.qux.a_str") == Some("rts") &&
        cfg.lookup[Boolean]("baz.qux.a_bool") == Some(false) &&
        cfg.lookup[List[Int]]("baz.an_int_list") == Some(List(4,5,6)) &&
        cfg.lookup[List[String]]("baz.a_str_list") == Some(List("d","e","f"))
      }
  }.unsafeRunSync
}
