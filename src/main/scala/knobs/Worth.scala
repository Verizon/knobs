package knobs

import scalaz._

/** A value that is either required or optional. */
sealed trait Worth[A] {
  def map[B](f: A => B): Worth[B]
  def worth: A
}
case class Required[A](worth: A) extends Worth[A] {
  def map[B](f: A => B): Worth[B] =
    Required(f(worth))
}
case class Optional[A](worth: A) extends Worth[A] {
  def map[B](f: A => B): Worth[B] =
    Optional(f(worth))
}

object Worth {
  implicit val worthFunctor: Functor[Worth] = new Functor[Worth] {
    def map[A,B](wa: Worth[A])(f: A => B) = wa map f
  }
}

