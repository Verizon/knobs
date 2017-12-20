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

import cats.MonadError
import cats.effect.Sync
import cats.implicits._
import java.net.URL
import scala.io.Source

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

  private def or[F[_], A](x: F[A], y: => F[A])(implicit F: MonadError[F, Throwable]): F[A] =
    x.attempt.flatMap {
      case Left(_)  => y
      case Right(a) => F.pure(a)
    }

  /**
   * attempt to read an item of metadata from the AWS metadata service in under 300ms.
   */
  private def fetch[F[_]](child: Path, parent: Path = root, version: Path = revision)(implicit F: Sync[F]): F[String] =
    F.delay {
      val conn = new URL(s"$parent/$version/$child").openConnection
      conn.setConnectTimeout(300)
      conn.setReadTimeout(300)
      Source.fromInputStream(conn.getInputStream).mkString
    }

  private def convert[F[_]](field: String, section: String = "aws", formatter: String => String = defaultFormating)(response: String)(implicit F: MonadError[F, Throwable]): F[Config] =
    Config.parse(s"$section { $field = ${formatter(response)} }").fold(F.raiseError, F.pure)

  def userdata[F[_]](implicit F: Sync[F]): F[Config] =
    or(
      fetch("user-data").flatMap(response => Config.parse(response).fold(F.raiseError, F.pure)),
      F.pure(Config.empty)
    )

  def instance[F[_]: Sync]: F[Config] =
    fetch("meta-data/instance-id").flatMap(convert[F]("instance-id"))

  def ami[F[_]: Sync]: F[Config] =
    fetch("meta-data/ami-id").flatMap(convert[F]("ami-id"))

  def securitygroups[F[_]: Sync]: F[Config] = fetch("meta-data/security-groups").flatMap(
    convert[F]("security-groups", formatter = x => "[" + x.split('\n').map(x => "\""+x+"\"").mkString(", ") + "]"  ))

  def zone[F[_]: Sync]: F[Config] =
    fetch("meta-data/placement/availability-zone").flatMap(convert[F]("availability-zone"))

  def region[F[_]: Sync]: F[Config] =
    fetch("meta-data/placement/availability-zone").map(_.dropRight(1)).flatMap(convert[F]("region"))

  def localIP[F[_]: Sync]: F[Config] =
    fetch("meta-data/local-ipv4").flatMap(convert[F]("local-ipv4", section = "aws.network"))

  def publicIP[F[_]: Sync]: F[Config] =
    fetch("meta-data/public-ipv4").flatMap(convert[F]("public-ipv4", section = "aws.network"))

  /**
   * Overhead for calling this mofo is high, so its not the kind of
   * thing you want to call anymore than once, but its value is cached internally
   * within the Config object that this is composed with.
   */
  def config[F[_]](implicit F: Sync[F]): F[Config] =
    or (for {
      a <- instance
      b <- ami
      c <- zone
      d <- securitygroups
      e <- localIP
      f <- or(publicIP, F.pure(Config.empty)) // machines in vpc do not have public ip
      g <- userdata
      h <- region
    } yield a ++ b ++ c ++ d ++ e ++ f ++ g ++ h, F.pure(Config.empty)) // fallback for running someplace other than aws.
}
