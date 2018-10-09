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
import cats.effect.ConcurrentEffect
import cats.implicits._
import cats.effect.concurrent.Ref

/**
 * Global configuration data. This is the top-level config from which
 * `Config` values are derived by choosing a root location.
 */
case class BaseConfig[F[_]](paths: Ref[F, List[(Name, KnobsResource)]],
                      cfgMap: Ref[F, Env],
                      subs: Ref[F, Map[Pattern, List[ChangeHandler[F]]]]) {

  /**
   * Get the `MutableConfig` at the given root location.
   */
  def mutableAt(root: String): MutableConfig[F] =
    MutableConfig("", this).subconfig(root)

  /**
   * Get the `Config` at the given root location
   */
  def at(root: String)(implicit F: Functor[F]): F[Config] =
    cfgMap.get.map(Config(_).subconfig(root))

  def reload(implicit F: ConcurrentEffect[F]): F[Unit] = for {
    ps <- paths.get
    mp <- loadFiles(ps.map(_._2)).flatMap(flatten(ps, _))
    m  <- cfgMap.modify(m => (mp, m))
    s  <- subs.get
    _ <- notifySubscribers(m, mp, s)
  } yield ()
}


