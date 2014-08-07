package knobs

import scala.collection.JavaConversions._
import scalaz.concurrent.Task
import org.apache.curator.framework._
import org.apache.curator.retry._

case class ZNode(connectionString: String, path: Path)

object Zookeeper {
  val retryPolicy = new ExponentialBackoffRetry(1000, 3)

  private def withClient[A](loc: String)(k: CuratorFramework => Task[A]): Task[A] =
    Task {
      val client = CuratorFrameworkFactory.newClient(loc, retryPolicy)
      client.start
      client
    }.flatMap(c => k(c).onFinish(_ => Task(c.close)))

  implicit val zkResource: Watchable[ZNode] = new Watchable[ZNode] {
    def resolve(r: ZNode, child: Path): ZNode =
      r.copy(path = Resource.resolveName(r.path, child))
    def load(node: Worth[ZNode]) = {
      val ZNode(location, path) = node.worth
      withClient(location) { c =>
        Resource.loadFile(node, Task {
          new String(c.getData.forPath(path).map(_.toChar))
        })
      }
    }
    def watch(node: Worth[Znode]) = for {
      ds <- load(node).flatMap
      rs <- recursiveImports(node.worth, ds)
      ticks <- withClient(node.worth.connectionString) { c =>
        Process.emitAll(rs).flatMap {
          case ZNode(_, path) =>
            Task.async(k =>
              c.getData.usingWatcher(new CuratorWatcher {
                def process(p: WatchedEvent) = p.eventType match {
                  case NodeDataChanged => k(())
                  case _ => ()
                }
              }).forPath(path))
        }
      }
    } yield (ds, ticks)
  }

}
