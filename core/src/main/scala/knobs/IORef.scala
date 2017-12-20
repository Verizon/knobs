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

import cats.effect.Sync
import cats.implicits._

/** An atomically updatable reference, guarded by the `IO` monad. */
sealed abstract class IORef[F[_], A] {
  def read: F[A]
  def write(value: A): F[Unit]
  def atomicModify[B](f: A => (A, B)): F[B]
  def modify(f: A => A): F[Unit] =
    atomicModify(a => (f(a), ()))
}

object IORef {
  def apply[F[_], A](value: A)(implicit F: Sync[F]): F[IORef[F, A]] = F.delay { new IORef[F, A] {
    val ref = new AtomicReference(value)
    def read = F.delay(ref.get)
    def write(value: A) = F.delay(ref.set(value))
    def atomicModify[B](f: A => (A, B)) = for {
      a <- read
      (a2, b) = f(a)
      p <- F.delay(ref.compareAndSet(a, a2))
      r <- if (p) F.pure(b) else atomicModify(f)
    } yield r
  } }
}
