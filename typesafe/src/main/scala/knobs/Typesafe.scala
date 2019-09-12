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

import cats.effect.Sync
import cats.implicits._
import com.typesafe.config.{Config => TC, _}
import scala.collection.JavaConverters._
import scala.reflect.ClassTag

/**
  * Adapts a Typesafe Config to a Knobs Config.
  * 
  * Caution: Typesafe Config's grammar does not distinguish a duration
  * type from other String values.  This means that durations are not
  * extracted in knobs in the same way as using the native Typesafe
  * Config API:
  * 
  * {{{
  * scala> val ts = ConfigFactory.parseString(""" "dur" = 3 seconds """)
  * ts: com.typesafe.config.Config = Config(SimpleConfigObject({"dur":"3 seconds"}))
  * 
  * scala> ts.getDuration("dur", java.util.concurrent.TimeUnit.MILLISECONDS)
  * res0: Long = 3000
  * 
  * scala> val k = knobs.Typesafe.config[IO](ts).unsafeRunSync
  * k: knobs.Config = Config(Map(dur -> CfgText(3 seconds)))
  * 
  * scala> k.lookup[Duration]("dur")
  * res1: Option[scala.concurrent.duration.Duration] = None
  * 
  * scala> k.lookup[String]("dur")
  * res2: Option[String] = Some(3 seconds)
  * }}}
  */
object Typesafe {
  /** Loads the default Typesafe Config via `Config.load` and adapts to
    * a Knobs Config. */
  def defaultConfig[F[_]](implicit F: Sync[F]): F[Config] =
    F.delay(ConfigFactory.load).flatMap(config(_))

  /** Adapts the specified Typesafe Config to a Knobs Config */
  def config[F[_]](cfg: TC)(implicit F: Sync[F]): F[Config] = F.delay {
    Config(convertTypesafeConfig(cfg))
  }

  private def convertList(list: ConfigList): CfgList = {
    def unwrap[T](c: ConfigValue)(implicit ev: ClassTag[T]): T =
      c.unwrapped match {
        case t: T => t
        case _ =>
          sys.error(s"Can't convert $c to underlying type ${ev.runtimeClass.getName}")
      }

    val items: List[CfgValue] =
      list.asScala.toList.flatMap { v =>
        v.valueType match {
          case ConfigValueType.NULL => None
          case ConfigValueType.BOOLEAN =>
            Some(CfgBool(unwrap[Boolean](v)))
          case ConfigValueType.LIST =>
            Some(convertList(unwrap[ConfigList](v)))
          case ConfigValueType.NUMBER =>
            Some(CfgNumber(unwrap[Number](v).doubleValue))
          case ConfigValueType.STRING =>
            Some(CfgText(unwrap[String](v)))
          case _ =>
            sys.error(s"Can't convert $v to a CfgValue")
        }
      }

    CfgList(items)
  }

  private def convertTypesafeConfig(cfg: TC) = {
    cfg.entrySet.asScala.toSet.foldLeft(Config.empty.env) {
      case (m, entry) => {
        val (k, v) = (entry.getKey, entry.getValue)
        v.valueType match {
          case ConfigValueType.OBJECT => m
          case ConfigValueType.NULL => m
          case _ =>
            def convert(v: ConfigValue): CfgValue = v.valueType match {
              case ConfigValueType.BOOLEAN =>
                CfgBool(cfg.getBoolean(k))
              case ConfigValueType.LIST =>
                convertList(cfg.getList(k))
              case ConfigValueType.NUMBER =>
                CfgNumber(cfg.getNumber(k).doubleValue)
              case ConfigValueType.STRING =>
                CfgText(cfg.getString(k))
              case _ => sys.error(s"Can't convert $v to a CfgValue")
            }
            m + (k -> convert(v))
        }
      }
    }
  }
}
