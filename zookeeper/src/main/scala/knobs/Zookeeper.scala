package knobs

import scala.collection.JavaConversions._
import scalaz.concurrent.Task
import org.apache.curator.framework._
import org.apache.curator.retry._

object Zookeeper {
  def config(zkLocation: String, path: String) = Task {
    val retryPolicy = new ExponentialBackoffRetry(1000, 3)
    val client = CuratorFrameworkFactory.newClient(zkLocation, retryPolicy)
    client.start
  }
}
