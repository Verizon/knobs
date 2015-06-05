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

/**
 * A ZNode contains a `path` to a node in the ZooKeeper tree
 * provided by the given `client`. The `client` is assumed to be started.
 * Knobs does not do any kind of managing of ZooKeeper connections.
 */
case class ZNode(client: CuratorFramework, path: Path)

object ZooKeeper {

  private val defaultCfg = List(Required(FileResource(new File("/usr/share/oncue/etc/zookeeper.cfg")) or
    ClassPathResource("oncue/zookeeper.cfg")))

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

  private def doZK(config: List[KnobsResource]): Task[(ResourceBox, CuratorFramework)] = {

    val retryPolicy = new ExponentialBackoffRetry(1000, 3)

    for {
      cfg <- knobs.loadImmutable(config)
      loc  = cfg.require[String]("zookeeper.connection-string")
      path = cfg.require[String]("zookeeper.path-to-config")
      c <- Task(CuratorFrameworkFactory.newClient(loc, retryPolicy))
      _ <- Task(c.start)
    } yield (Watched(ZNode(c, path)), c)
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
  def withDefault(k: ResourceBox => Task[Unit]): Task[Unit] = safe(k)

  /**
   * Task-based API. Works just like `withDefault` except it loads configuration from
   * specified resource.
   *
   * Example usage:
   *
   * ```
   * import knobs._
   *
   * ZooKeeper.fromResource(List(Required(ClassPathResource("speech-service.conf")))) { r => for {
   *   cfg <- load(Required(r))
   *   // Application code here
   * } yield () }.run
   * ```
   */
  def fromResource(customConfig: List[KnobsResource])(k: ResourceBox => Task[Unit]): Task[Unit] = safe(k, customConfig)

  protected def safe(k: ResourceBox => Task[Unit], config: List[KnobsResource] = defaultCfg): Task[Unit] = for {
    p <- doZK(config)
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
  def unsafeDefault: (ResourceBox, Task[Unit]) = unsafe()

  /**
   * Unsafe API. Works just like `unsafeDefault` except it loads configuration from
   * specified resource
   *
   * Example usage:
   *
   * ```
   * import knobs._
   *
   * val (r, close) = ZooKeeper.unsafeFromResource(List(Required(ClassPathResource("my-service.conf"))))
   * // Application code here
   * close.run
   * ```
   */
  def unsafeFromResource(customConfig: List[KnobsResource]): (ResourceBox, Task[Unit]) = unsafe(customConfig)

  protected def unsafe(config: List[KnobsResource] = defaultCfg): (ResourceBox, Task[Unit]) = {
    val (box, c) = doZK(config).run
    (box, Task(c.close))
  }

}
