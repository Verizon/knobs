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

import org.apache.curator.test._
import org.scalacheck._
import org.scalacheck.Prop._
import scalaz.concurrent.Task
import Resource._
import org.apache.zookeeper._
import org.apache.curator.framework.api._
import org.apache.curator.framework._
import org.apache.curator.retry._
import java.util.concurrent.CountDownLatch
import compatibility._

object ZooKeeperTests extends Properties("ZooKeeper") {

  val retryPolicy = new ExponentialBackoffRetry(1000, 3)

  import ZooKeeper._

  property("load config") = {
    val server = new TestingServer
    val loc = "localhost:" + server.getPort
    server.start
    val c = CuratorFrameworkFactory.newClient(loc, retryPolicy)
    c.start
    c.create.forPath("/knobs.cfg", "foo = 10\n".toArray.map(_.toByte))
    val n = load(List(ZNode(c, "/knobs.cfg").required)).flatMap(cfg =>
      cfg.require[Int]("foo")).unsafePerformSync
    c.close
    server.close
    n == 10
  }

  property("watch config") = {
    val server = new TestingServer
    val loc = "localhost:" + server.getPort
    server.start
    val c = CuratorFrameworkFactory.newClient(loc, retryPolicy)
    c.start
    c.create.forPath("/knobs.cfg", "foo = 10\n".toArray.map(_.toByte))
    val latch = new CountDownLatch(1)
    val prg = for {
      ref <- IORef(0)
      cfg <- load(List(Required(Watched(ZNode(c, "/knobs.cfg")))))
      n1 <- cfg.require[Int]("foo")
      _ <- cfg.subscribe(Exact("foo"), {
        case ("foo", Some(CfgNumber(n))) =>
          ref.write(n.toInt).flatMap(_ => Task.delay(latch.countDown))
        case _ => Task.delay(latch.countDown)
      })
      _ <- Task {
        c.setData.forPath("/knobs.cfg", "foo = 20\n".toArray.map(_.toByte))
        latch.await
      }
      n2 <- ref.read
    } yield n1 == 10 && n2 == 20
    val r = prg.unsafePerformSync
    c.close
    server.close
    r
  }
}
