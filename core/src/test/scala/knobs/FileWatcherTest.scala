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

import cats.effect.concurrent.Ref
import java.nio.file.{ Files, Paths }
import java.util.concurrent.CountDownLatch
import org.scalacheck._
import org.scalacheck.Prop._
import scala.concurrent.ExecutionContext.Implicits.global

import cats.effect.IO

object FileWatcherTests extends Properties("FileWatch") {

  property("file watch") = {
    val mutantUri = Thread.currentThread.getContextClassLoader.getResource("mutant.cfg").toURI
    val mutantPath = Paths.get(mutantUri)
    val latch = new CountDownLatch(1)
    val prg = for {
      ref <- Ref.of[IO, String]("")
      _   <- IO(Files.write(mutantPath, "foo = \"bletch\"\n".getBytes))
      cfg <- load[IO](List(Required(FileResource(mutantPath.toFile))))
      _ <- cfg.subscribe(Exact("foo"), {
        case ("foo", Some(t: CfgText)) =>
          ref.set(t.pretty).flatMap(_ => IO(latch.countDown))
        case _ => {
          IO(latch.countDown)
        }
      })
      _   <- IO(Thread.sleep(1000))
      _   <- IO(Files.write(mutantPath, "foo = \"bar\"\n".getBytes))
      _   <- IO(latch.await)
      r   <- ref.get
    } yield r == "\"bar\""
    prg.unsafeRunSync
  }
}
