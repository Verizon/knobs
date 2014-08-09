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
      cfg.require[Int]("foo")).run
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
          ref.write(n.toInt).flatMap(_ => Task(latch.countDown))
        case _ => Task(latch.countDown)
      })
      _ <- Task {
        c.setData.forPath("/knobs.cfg", "foo = 20\n".toArray.map(_.toByte))
        latch.await
      }
      n2 <- ref.read
    } yield n1 == 10 && n2 == 20
    val r = prg.run
    c.close
    server.close
    r
  }
}
