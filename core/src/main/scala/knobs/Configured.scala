package knobs

import scalaz.syntax.traverse._
import scalaz.syntax.applicative._
import scalaz.std.list._
import scalaz.std.option._
import scalaz.Monad

/**
 * The class of types that can be automatically and safely
 * converted from a `CfgValue` to a destination type. If conversion
 * fails because the types are not compatible, `None` is returned.
 */
trait Configured[A] {
  def apply(v: CfgValue): Option[A]
}

object Configured {
  def apply[A:Configured]: Configured[A] = implicitly[Configured[A]]

  def apply[A](f: CfgValue => Option[A]): Configured[A] = new Configured {
    def apply(v: CfgValue) = f(v)
  }

  implicit val configuredMonad: Monad[Configured] = new Monad[Configured] {
    def point[A](a: => A) = new Configured[A] {
      def apply(v: CfgValue) = Some(a)
    }
    def bind[A,B](ca: Configured[A])(f: A => Configured[B]) = new Configured[A] {
      def apply(v: CfgValue) = f(ca(v))(v)
    }
  }

  implicit val configuredValue: Configured[CfgValue] = new Configured[CfgValue] {
    def apply(a: CfgValue) = Some(a)
  }

  implicit val configuredInt: Configured[Int] = new Configured[Int] {
    def apply(a: CfgValue) = a match {
      case CfgNumber(n) if (n % 1 == 0.0) => Some(n.toInt)
      case _ => None
    }
  }

  implicit val configuredDouble: Configured[Double] = new Configured[Double] {
    def apply(a: CfgValue) = a match {
      case CfgNumber(n) => Some(n)
      case _ => None
    }
  }

  implicit val configuredString: Configured[String] = new Configured[String] {
    def apply(a: CfgValue) = a match {
      case CfgText(s) => Some(s)
      case _ => None
    }
  }

  implicit val configuredBool: Configured[Boolean] = new Configured[Boolean] {
    def apply(a: CfgValue) = a match {
      case CfgBool(b) => Some(b)
      case _ => None
    }
  }

  implicit def configuredList[A](implicit A: Configured[A]): Configured[List[A]] =
    new Configured[List[A]] {
      def apply(v: CfgValue) = v match {
        case CfgList(xs) => xs.traverse(A.apply)
        case _ => None
      }
    }

  implicit def configuredTuple2[A,B](
    implicit A: Configured[A], B: Configured[B]
  ): Configured[(A, B)] = new Configured[(A, B)] {
    def apply(v: CfgValue) = v match {
      case CfgList(a :: b :: Nil) => (A(a) |@| B(b))((_, _))
      case _ => None
    }
  }

  implicit def configuredTuple3[A,B,C](
    implicit A: Configured[A], B: Configured[B], C: Configured[C]
  ): Configured[(A, B, C)] = new Configured[(A, B, C)] {
    def apply(v: CfgValue) = v match {
      case CfgList(a :: b :: c :: Nil) => (A(a) |@| B(b) |@| C(c))((_, _, _))
      case _ => None
    }
  }

  implicit def configuredTuple4[A,B,C,D](
    implicit A: Configured[A], B: Configured[B], C: Configured[C], D: Configured[D]
  ): Configured[(A, B, C, D)] = new Configured[(A, B, C, D)] {
    def apply(v: CfgValue) = v match {
      case CfgList(a :: b :: c :: d :: Nil) =>
        ((A(a) |@| B(b) |@| C(c) |@| D(d)))((_, _, _, _))
      case _ => None
    }
  }
}
