package knobs

sealed trait Trivial

object Trivial {
  implicit val trivial: Trivial = new Trivial {}
}
