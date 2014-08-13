package knobs

import scala.collection.JavaConversions._
import scalaz.concurrent.Task
import org.apache.curator.framework._
import org.apache.curator.framework.api._
import org.apache.curator.retry._
import org.apache.zookeeper.WatchedEvent
import org.apache.zookeeper.Watcher.Event.EventType._
import scalaz.stream._
import scalaz.\/
import \/._
import scalaz.stream.merge.mergeN
import java.io.File
import Resource._

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

  private def doZK: Task[(ResourceBox, CuratorFramework)] = {
    val retryPolicy = new ExponentialBackoffRetry(1000, 3)
    for {
      config <- knobs.loadImmutable(
         List(Required(FileResource(new File("/usr/share/oncue/etc/zookeeper.cfg")) or
                       ClassPathResource("oncue/zookeeper.cfg"))))
      loc  = config.require[String]("zookeeper.connectionString")
      path = config.require[String]("zookeeper.pathToConfig")
      c <- Task(CuratorFrameworkFactory.newClient(loc, retryPolicy))
      _ <- Task(c.start)
    } yield (box(ZNode(c, path)), c)
  }

  /**
   * Task-based API. Loads the standard configuration from
   * /usr/share/oncue/etc/zookeeper.cfg or, failing that,
   * from the classpath at oncue/zookeeper.cfg.
   *
   * Expects `zookeeper.connectionString` to be defined in that cfg file,
   * which is a string of the form "hostname:port".
   *
   * Expects `zookeeper.pathToConfig` to be defined as well, which is the
   * zookeeper path to the ZNode that contains the configuration.
   *
   * Manages the lifecycle of the ZooKeeper connection for you.
   *
   * Usage example:
   *
   * ```
   * import knobs._
   *
   * ZooKeeper.withDefault { r => for {
   *   cfg <- load(Required(r))
   *   // Application code here
   * } yield () }.run
   * ```
   */
  def withDefault(k: ResourceBox => Task[Unit]): Task[Unit] = for {
    p <- doZK
    (box, c) = p
    _ <- k(box)
    _ <- Task(c.close)
  } yield ()

  /**
   * Unsafe API. Loads the standard config, just like `withDefault`,
   * except this returns the resource together with a `Task` that you can
   * `run` to close the Zookeeper connection at the end of your application.
   *
   * Example usage:
   *
   * ```
   * import knobs._
   *
   * val (r, close) = ZooKeeper.unsafeDefault
   * // Application code here
   * close.run
   * ```
   */
  def unsafeDefault: (ResourceBox, Task[Unit]) = {
    val (box, c) = doZK.run
    (box, Task(c.close))
  }
}
