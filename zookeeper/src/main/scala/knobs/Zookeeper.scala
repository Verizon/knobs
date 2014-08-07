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

  implicit def zkResource: Resource[ZNode] = new Resource[ZNode] {
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
  }
}
