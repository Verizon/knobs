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

import java.util.concurrent.atomic.AtomicReference

import cats.effect.IO

/** An atomically updatable reference, guarded by the `IO` monad. */
sealed abstract class IORef[A] {
  def read: IO[A]
  def write(value: A): IO[Unit]
  def atomicModify[B](f: A => (A, B)): IO[B]
  def modify(f: A => A): IO[Unit] =
    atomicModify(a => (f(a), ()))
}

object IORef {
  def apply[A](value: A): IO[IORef[A]] = IO { new IORef[A] {
    val ref = new AtomicReference(value)
    def read = IO { ref.get }
    def write(value: A) = IO { ref.set(value) }
    def atomicModify[B](f: A => (A, B)) = for {
      a <- read
      (a2, b) = f(a)
      p <- IO { ref.compareAndSet(a, a2) }
      r <- if (p) IO.pure(b) else atomicModify(f)
    } yield r
  } }
}
