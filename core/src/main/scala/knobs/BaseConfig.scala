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

import cats.effect.IO

/**
 * Global configuration data. This is the top-level config from which
 * `Config` values are derived by choosing a root location.
 */
case class BaseConfig(paths: IORef[List[(Name, KnobsResource)]],
                      cfgMap: IORef[Env],
                      subs: IORef[Map[Pattern, List[ChangeHandler]]]) {

  /**
   * Get the `MutableConfig` at the given root location.
   */
  def mutableAt(root: String): MutableConfig =
    MutableConfig("", this).subconfig(root)

  /**
   * Get the `Config` at the given root location
   */
  def at(root: String): IO[Config] =
    cfgMap.read.map(Config(_).subconfig(root))

  lazy val reload: IO[Unit] = for {
    ps <- paths.read
    mp <- loadFiles(ps.map(_._2)).flatMap(flatten(ps, _))
    m  <- cfgMap.atomicModify(m => (mp, m))
    s  <- subs.read
    _ <- notifySubscribers(m, mp, s)
  } yield ()
}


