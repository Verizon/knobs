package knobs

/** A directive in a configuration file */
sealed trait Directive
case class Import(path: Path) extends Directive
case class Bind(name: Name, value: CfgValue) extends Directive
case class Group(name: Name, directives: List[Directive]) extends Directive

/** A bound configuration value */
sealed trait CfgValue {
  def convertTo[A:Configured]: Option[A] = implicitly[Configured[A]].apply(this)
}
case class CfgBool(value: Boolean) extends CfgValue
case class CfgText(value: String) extends CfgValue
case class CfgNumber(value: Double) extends CfgValue
case class CfgList(value: List[CfgValue]) extends CfgValue

sealed trait Interpolation
case class Literal(text: String) extends Interpolation
case class Interpolate(text: String) extends Interpolation

