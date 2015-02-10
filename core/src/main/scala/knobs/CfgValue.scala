package knobs

/** A bound configuration value */
sealed trait CfgValue {
  def convertTo[A:Configured]: Option[A] = implicitly[Configured[A]].apply(this)
  def pretty: String
}
case class CfgBool(value: Boolean) extends CfgValue {
  val pretty = value.toString
}
case class CfgText(value: String) extends CfgValue {
  val pretty = "\"" + value + "\""
}
case class CfgNumber(value: Double) extends CfgValue {
  val pretty = value.toString
}
case class CfgList(value: List[CfgValue]) extends CfgValue {
  lazy val pretty = {
    val s = value.map(_.pretty).mkString(",")
    s"[$s]"
  }
}

import scala.concurrent.duration.Duration

case class CfgDuration(value: Duration) extends CfgValue {
  val pretty = value.toString
}

