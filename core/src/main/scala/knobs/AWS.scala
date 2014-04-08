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

  def fetch(child: Path, parent: Path = root, version: Path = revision): Task[String] =
    Task(Source.fromInputStream(new URL(s"$parent/$version/$child").openConnection.getInputStream).mkString)

  def convert(field: String, f: String => String = x => "\"" +x+ "\"")(response: String): Task[Config] ={
    val xxx = s"aws { $field = ${f(response)} }"
    println(">>>>> " + xxx)
    Config.parse(xxx).fold(Task.fail, Task.now)
  }

  def userdata: Task[Config] = fetch("user-data")
    .flatMap(response => Config.parse(response)
    .fold(Task.fail, Task.now)) or Task.now(Config.empty)

  def instance: Task[Config] =
    fetch("meta-data/instance-id").flatMap(convert("instance-id"))

  def ami: Task[Config] =
    fetch("meta-data/ami-id").flatMap(convert("ami-id"))

  def securityGroups: Task[Config] = fetch("meta-data/security-groups").flatMap(
    convert("security-groups", x => "[" + x.split('\n').map(x => "\""+x+"\"").mkString(", ") + "]"  ))

  lazy val config: Task[Config] =
    for {
      a <- userdata
      b <- instance
      c <- ami
      d <- securityGroups
    } yield a ++ b ++ c ++ d
}
