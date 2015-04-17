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

import scalaz._

/** A value that is either required or optional. */
sealed trait Worth[A] {
  def map[B](f: A => B): Worth[B]
  def worth: A
  def isRequired: Boolean
}
case class Required[A](worth: A) extends Worth[A] {
  def map[B](f: A => B): Worth[B] =
    Required(f(worth))
  def isRequired = true
}
case class Optional[A](worth: A) extends Worth[A] {
  def map[B](f: A => B): Worth[B] =
    Optional(f(worth))
  def isRequired = false
}

object Worth {
  implicit val worthFunctor: Functor[Worth] = new Functor[Worth] {
    def map[A,B](wa: Worth[A])(f: A => B) = wa map f
  }
}

