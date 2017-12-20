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

import scala.concurrent.duration.Duration

/** A bound configuration value */
sealed abstract class CfgValue extends Product with Serializable {
  def convertTo[A: Configured]: Option[A] = Configured[A].apply(this)
  def pretty: String
}

final case class CfgBool(value: Boolean) extends CfgValue {
  val pretty = value.toString
}

final case class CfgText(value: String) extends CfgValue {
  val pretty = "\"" + value + "\""
}

final case class CfgNumber(value: Double) extends CfgValue {
  val pretty = value.toString
}

final case class CfgList(value: List[CfgValue]) extends CfgValue {
  lazy val pretty = {
    val s = value.map(_.pretty).mkString(",")
    s"[$s]"
  }
}

final case class CfgDuration(value: Duration) extends CfgValue {
  val pretty = value.toString
}
