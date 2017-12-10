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

import scala.concurrent.duration.{Duration, FiniteDuration}

import cats.{Monad, StackSafeMonad}
import cats.implicits._

/**
 * The class of types that can be automatically and safely
 * converted from a `CfgValue` to a destination type. If conversion
 * fails because the types are not compatible, `None` is returned.
 */
trait Configured[A] {
  def apply(v: CfgValue): Option[A]
}

object Configured {

  // second parameter (of any non-Unit) is required to get around SAM-derived ambiguities
  def apply[A](implicit A: Configured[A], T: Trivial): Configured[A] = A

  def apply[A](f: CfgValue => Option[A]): Configured[A] = new Configured[A] {
    def apply(v: CfgValue) = f(v)
  }

  implicit val configuredMonad: Monad[Configured] = new StackSafeMonad[Configured] {
    def pure[A](a: A) = new Configured[A] {
      def apply(v: CfgValue) = Some(a)
    }

    def flatMap[A,B](ca: Configured[A])(f: A => Configured[B]) = new Configured[B] {
      def apply(v: CfgValue) = ca(v).flatMap(f(_)(v))
    }
  }

  implicit val configuredDuration: Configured[Duration] = new Configured[Duration]{
    def apply(a: CfgValue) = a match {
      case CfgDuration(b) => Some(b)
      case _ => None
    }
  }

  implicit val configuredFiniteDuration: Configured[FiniteDuration] = new Configured[FiniteDuration]{
    def apply(a: CfgValue) = configuredDuration(a) collect {
      case d: FiniteDuration => d
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
      case CfgList(a :: b :: Nil) => (A(a), B(b)).mapN((_, _))
      case _ => None
    }
  }

  implicit def configuredTuple3[A,B,C](
    implicit A: Configured[A], B: Configured[B], C: Configured[C]
  ): Configured[(A, B, C)] = new Configured[(A, B, C)] {
    def apply(v: CfgValue) = v match {
      case CfgList(a :: b :: c :: Nil) => (A(a), B(b), C(c)).mapN((_, _, _))
      case _ => None
    }
  }

  implicit def configuredTuple4[A,B,C,D](
    implicit A: Configured[A], B: Configured[B], C: Configured[C], D: Configured[D]
  ): Configured[(A, B, C, D)] = new Configured[(A, B, C, D)] {
    def apply(v: CfgValue) = v match {
      case CfgList(a :: b :: c :: d :: Nil) => (A(a), B(b), C(c), D(d)).mapN((_, _, _, _))
      case _ => None
    }
  }
}
