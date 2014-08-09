package knobs

import scala.collection.JavaConversions._
import scalaz.concurrent.Task
import org.apache.curator.framework._
import org.apache.curator.framework.api._
import org.apache.zookeeper.WatchedEvent
import org.apache.zookeeper.Watcher.Event.EventType._
import scalaz.stream._
import scalaz.\/
import \/._
import scalaz.stream.merge.mergeN

/**
 * A ZNode contains a `path` to a node in the ZooKeeper tree
 * provided by the given `client`. The `client` is assumed to be started.
 * Knobs does not do any kind of managing of ZooKeeper connections.
 */
case class ZNode(client: CuratorFramework, path: Path)

object ZooKeeper {
  /**
   * A process that produces an event when the given path's data changes.
   * This process only contains a single event.
   * To subscribe to multiple events, call `repeat` on the result of this method.
   * With only one event, we prevent multiple reloads occurring simultaneously
   * in `Watched` znodes.
   */
  def watchEvent(c: CuratorFramework, path: Path): Process[Task, WatchedEvent] =
    Process.eval(Task.async { (k: (Throwable \/ WatchedEvent) => Unit) =>
      val _ = try {
        c.getData.usingWatcher(new CuratorWatcher {
          def process(p: WatchedEvent) = p.getType match {
            case NodeDataChanged => k(right(p))
            case _ => ()
          }
        }).forPath(path)
        ()
      } catch { case e: Exception => k(left(e)) }
    }).repeat

  implicit val zkResource: Watchable[ZNode] = new Watchable[ZNode] {
    def resolve(r: ZNode, child: Path): ZNode =
      r.copy(path = Resource.resolveName(r.path, child))
    def load(node: Worth[ZNode]) = {
      val ZNode(c, path) = node.worth
      Resource.loadFile(node, Task {
        new String(c.getData.forPath(path).map(_.toChar))
      })
    }
    def watch(node: Worth[ZNode]) = for {
      ds <- load(node)
      rs <- recursiveImports(node.worth, ds)
      reloads <- Task { Process.emitAll(node.worth +: rs).map {
        case ZNode(c, path) => watchEvent(c, path).map(_ => ())
      }}
    } yield (ds, mergeN(reloads))
  }

}
