package knobs

import java.net.{URL,URLConnection}
import scala.io.Source
import scalaz.concurrent.Task

/**
 * Reads a set of configuration information from the running machine within
 * the AWS environment. Amazon documentaiton can be found here:
 *
 * http://docs.aws.amazon.com/AWSEC2/latest/UserGuide/AESDG-chapter-instancedata.html
 */
object aws {
  type Path = String

  // supplied by amazon.
  private val root = "http://169.254.169.254"
  // using a fixed version to avoid api shift over time.
  private val revision = "2012-01-12"

  private val defaultFormating: String => String = x => "\"" +x+ "\""

  /**
   * attempt to read an item of metadata from the AWS metadata service in under 300ms.
   */
  private def fetch(child: Path, parent: Path = root, version: Path = revision): Task[String] =
    Task(Source.fromInputStream(new URL(s"$parent/$version/$child").openConnection.getInputStream).mkString).timed(300)(scalaz.stream.DefaultScheduler)

  private def convert(field: String, section: String = "aws", formatter: String => String = defaultFormating)(response: String): Task[Config] =
    Config.parse(s"$section { $field = ${formatter(response)} }").fold(Task.fail, Task.now)

  def userdata: Task[Config] = fetch("user-data")
    .flatMap(response => Config.parse(response)
    .fold(Task.fail, Task.now)) or Task.now(Config.empty)

  def instance: Task[Config] =
    fetch("meta-data/instance-id").flatMap(convert("instance-id"))

  def ami: Task[Config] =
    fetch("meta-data/ami-id").flatMap(convert("ami-id"))

  def securitygroups: Task[Config] = fetch("meta-data/security-groups").flatMap(
    convert("security-groups", formatter = x => "[" + x.split('\n').map(x => "\""+x+"\"").mkString(", ") + "]"  ))

  def zone: Task[Config] =
    fetch("meta-data/placement/availability-zone").flatMap(convert("availability-zone"))

  def region: Task[Config] =
    fetch("meta-data/placement/availability-zone").map(_.dropRight(1)).flatMap(convert("region"))

  def localIP: Task[Config] =
    fetch("meta-data/local-ipv4").flatMap(convert("local-ipv4", section = "network"))

  def publicIP: Task[Config] =
    fetch("meta-data/public-ipv4").flatMap(convert("public-ipv4", section = "network"))

  /**
   * Overhead for calling this mofo is high, so its not the kind of
   * thing you want to call anymore than once, but its value is cached internally
   * within the Config object that this is composed with.
   */
  lazy val config: Task[Config] =
    (for {
      a <- instance
      b <- ami
      c <- zone
      d <- securitygroups
      e <- localIP
      f <- publicIP
      g <- userdata
      h <- region
    } yield a ++ b ++ c ++ d ++ e ++ f ++ g ++ h) or Task.now(Config.empty) // fallback for running someplace other than aws.
}
