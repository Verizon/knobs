package knobs

sealed trait Interpolation
case class Literal(text: String) extends Interpolation
case class Interpolate(text: String) extends Interpolation

