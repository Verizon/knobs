package knobs

/** A directive in a configuration file */
sealed trait Directive
case class Import(path: Path) extends Directive
case class Bind(name: Name, value: CfgValue) extends Directive
case class Group(name: Name, directives: List[Directive]) extends Directive

