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

import java.net.URI
import java.io.File
import java.nio.file.{ FileSystems, Path => P}
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.util.concurrent.{ Executors, ThreadFactory }
import scala.collection.JavaConverters._

import cats.Show
import cats.data.NonEmptyVector
import cats.effect.{Async, Sync, Concurrent}
import cats.implicits._
import fs2.Stream

import scala.concurrent.ExecutionContext

/** Resources from which configuration files can be loaded */
trait Resource[R] extends Show[R] {

  /**
   * Returns a resource that has the given `child` path resolved against `r`.
   * If the given path is absolute (not relative), a new resource to that path should be returned.
   * Otherwise the given path should be appended to this resource's path.
   * For example, if this resource is the URI "http://tempuri.org/foo/", and the given
   * path is "bar/baz", then the resulting resource should be the URI "http://tempuri.org/foo/bar/baz"
   */
  def resolve(r: R, child: Path): R

  /**
   * Loads a resource, returning a list of config directives in `IO`.
   */
  def load[F[_]](path: Worth[R])(implicit F: Sync[F]): F[List[Directive]]
}

trait Watchable[R] extends Resource[R] {
  def watch[F[_]](path: Worth[R])(implicit F: Concurrent[F]): F[(List[Directive], Stream[F, Unit])]
}

/** An existential resource. Equivalent to the (Haskell-style) type `exists r. Resource r ⇒ r` */
sealed trait ResourceBox {
  type R
  val R: Resource[R]
  val resource: R
  def or(b: ResourceBox) = Resource.box(Resource.or(resource, b.resource)(R, b.R))
  def required: KnobsResource = Required(this)
  def optional: KnobsResource = Optional(this)
  override def equals(other: Any) = other.asInstanceOf[ResourceBox].resource == resource
  override def hashCode = resource.hashCode
  def watchable: Option[Watchable[R]] = None
}

private [knobs] case class WatchBox[W](value: W, W: Watchable[W]) extends ResourceBox {
  type R = W
  val R = W
  val resource = value
  override def watchable = Some(W)
}

object Watched {
  def apply[R:Watchable](r: R): ResourceBox =
    WatchBox(r, implicitly[Watchable[R]])
}

object ResourceBox {
  def apply[R:Resource](r: R) = Resource.box(r)

  implicit def resourceBoxShow: Show[ResourceBox] = new Show[ResourceBox] {
    override def show(b: ResourceBox): String = b.R.show(b.resource)
  }
}


object FileResource {
  /**
   * Creates a new resource that loads a configuration from a file.
   * Optionally creates a process to watch changes to the file and
   * reload any `MutableConfig` if it has changed.
   */
  def apply(f: File, watched: Boolean = true): ResourceBox = {
    val _ = watched
    Watched(f.getCanonicalFile)
  }

  /**
   * Creates a new resource that loads a configuration from a file.
   * Does not watch the file for changes or reload the config automatically.
   */
  def unwatched(f: File): ResourceBox = Resource.box(f.getCanonicalFile)
}

object ClassPathResource {
  /**
   * Creates a new resource that loads a configuration from the classpath of the provided
   * `ClassLoader`, if given, otherwise by the `ClassLoader` of object `ClassPathResource`.
   */
  def apply(s: Path, loader: ClassLoader = getClass.getClassLoader): ResourceBox =
    Resource.box(Resource.ClassPath(s, loader))
}

object SysPropsResource {
  /**
   * Creates a new resource that gets config values from system properties
   * matching the given pattern.
   */
  def apply(p: Pattern): ResourceBox = Resource.box(p)
}

object URIResource {
  /**
   * Creates a new resource that loads a configuration from the given URI.
   * Note that the URI also needs to be a valid URL.
   * We are not using `java.net.URL` here because it has side effects.
   */
  def apply(u: URI): ResourceBox = Resource.box(u)
}

import java.nio.file.{WatchService,WatchEvent}

object Resource {
  type FallbackChain = NonEmptyVector[ResourceBox]
  def pool(name: String) = ExecutionContext.fromExecutorService(Executors.newCachedThreadPool(new ThreadFactory {
    def newThread(r: Runnable) = {
      val t = Executors.defaultThreadFactory.newThread(r)
      t.setDaemon(true)
      t.setName(name)
      t
    }
  }))

  // Thread pool for asynchronously watching config files
  val watchPool = pool("knobs-watch-service-pool")

  // Thread pool for notifying subcribers of changes to mutable configs
  val notificationPool = pool("knobs-notification-pool")

  private val watchService: WatchService = FileSystems.getDefault.newWatchService
  private def watchStream[F[_]](implicit F: Async[F]): Stream[F, WatchEvent[_]] =
    Stream.eval(Async.shift[F](watchPool) *> F.delay(watchService.take)).flatMap(s =>
      Stream.emits(s.pollEvents.asScala)).repeat

  /** Construct a new boxed resource from a valid `Resource` */
  def apply[B](r: B)(implicit B: Resource[B]): ResourceBox = box(r)

  // Box up a resource with the evidence that it is a resource.
  def box[B](r: B)(implicit B: Resource[B]): ResourceBox =
    new ResourceBox {
      type R = B
      val R = B
      val resource = r
    }

  /**
   * Convenience method for resolving a path-like name relative to another.
   * `resolve("foo/bar", "baz")` = `"foo/baz"`
   * `resolve("foo/bar/", "baz")` = `"foo/bar/baz"`
   * `resolve(_, "/bar")` = `"/bar"`
   */
  def resolveName(parent: String, child: String) =
    if (child.head == '/') child
    else parent.substring(0, parent.lastIndexOf('/') + 1) ++ child

  /**
   * Convenience method for loading a file-like resource.
   * `load` is a `IO` that does the work of turning the resource into a `String`
   * that gets parsed by the `ConfigParser`.
   */
  def loadFile[F[_], R:Resource](path: Worth[R], load: F[String])(implicit F: Sync[F]): F[List[Directive]] = {
    import ConfigParser.ParserOps
    for {
      es <- load.attempt
      r <- es.fold(ex =>
        path match {
          case Required(_) => F.raiseError(ex)
          case _ => F.pure(Nil)
        }, s => for {
          p <- F.delay(ConfigParser.topLevel.parse(s)).attempt.flatMap[Either[String, List[Directive]]] {
            case Left(ConfigError(_, err)) =>
              F.raiseError(ConfigError(path.worth, err))
            case Left(e)  => F.raiseError(e)
            case Right(a) => F.pure(a)
          }
          r <- p.fold[F[List[Directive]]](
            err => F.raiseError(ConfigError(path.worth, err)),
            ds  => F.pure(ds)
          )
        } yield r)
    } yield r
  }

  def failIfNone[F[_], A](a: Option[A], msg: String)(implicit F: Sync[F]): F[A] =
    a.map(F.pure).getOrElse(F.raiseError(new RuntimeException(msg)))

  def watchEvent[F[_]](path: P)(implicit F: Async[F]): F[Stream[F, WatchEvent[_]]] = {
    val dir =
      failIfNone(Option(path.getParent),
                 s"File $path has no parent directory. Please provide a canonical file name.")
    val file =
      failIfNone(Option(path.getFileName),
                 s"Path $path has no file name.")

    for {
      d <- dir
      _ <- F.delay(d.register(watchService, ENTRY_MODIFY))
      f <- file
    } yield watchStream.filter(_.context == f)
  }

  implicit def fileResource: Watchable[File] = new Watchable[File] {
    def resolve(r: File, child: String): File =
      new File(resolveName(r.getPath, child))

    def load[F[_]](path: Worth[File])(implicit F: Sync[F]) =
      loadFile(path, F.delay(scala.io.Source.fromFile(path.worth).mkString))

    override def show(r: File) = r.toString

    def watch[F[_]](path: Worth[File])(implicit F: Concurrent[F]) = for {
      ds <- load(path)
      rs <- recursiveImports(path.worth, ds)
      es <- (path.worth :: rs).traverse(f => watchEvent(f.toPath))
    } yield (ds, Stream.emits(es.map(_.map(_ => ()))).parJoinUnbounded)
  }

  implicit def uriResource: Resource[URI] = new Resource[URI] {
    def resolve(r: URI, child: String): URI = r resolve new URI(child)
    def load[F[_]](path: Worth[URI])(implicit F: Sync[F]) =
      loadFile(path, F.delay(scala.io.Source.fromURL(path.worth.toURL).mkString + "\n"))
    override def show(r: URI) = r.toString
  }

  final case class ClassPath(s: String, loader: ClassLoader)

  implicit def classPathResource: Resource[ClassPath] = new Resource[ClassPath] {
    def resolve(r: ClassPath, child: String) =
      ClassPath(resolveName(r.s, child), r.loader)
    def load[F[_]](path: Worth[ClassPath])(implicit F: Sync[F]) = {
      val r = path.worth
      loadFile(path, F.delay(r.loader.getResourceAsStream(r.s)) flatMap { x =>
        if (x == null) F.raiseError(new java.io.FileNotFoundException(r.s + " not found on classpath"))
        else F.delay(scala.io.Source.fromInputStream(x).mkString)
      })
    }
    override def show(r: ClassPath) = {
      val res = r.loader.getResource(r.s)
      if (res == null)
        s"missing classpath resource ${r.s}"
      else
        res.toString
    }
  }

  implicit def sysPropsResource: Resource[Pattern] = new Resource[Pattern] {
    import ConfigParser.ParserOps
    def resolve(p: Pattern, child: String) = p match {
      case Exact(s) => Exact(s"$p.$s")
      case Prefix(s) => Prefix(s"$p.$s")
    }
    def load[F[_]](path: Worth[Pattern])(implicit F: Sync[F]) = {
      val pat = path.worth
      for {
        ds <- F.delay(sys.props.toMap.filterKeys(pat matches _).map {
                   case (k, v) => Bind(k, ConfigParser.value.parse(v).toOption.getOrElse(CfgText(v)))
                 })
        r <- (ds.isEmpty, path) match {
          case (true, Required(_)) =>
            F.raiseError(new ConfigError(path.worth, s"Required system properties $pat not present."))
          case _ => F.pure(ds.toList)
        }
      } yield r
    }
    override def show(r: Pattern): String = s"System properties $r.*"
  }

  implicit def fallbackResource: Resource[FallbackChain] = new Resource[FallbackChain] {
    def resolve(p: FallbackChain, child: String) = {
      val a = p.head
      val as = p.tail
      NonEmptyVector(box(a.R.resolve(a.resource, child))(a.R), as.map(b =>
        box(b.R.resolve(b.resource, child))(b.R)
      ))
    }

    def load[F[_]](path: Worth[FallbackChain])(implicit F: Sync[F]) = {
      def loadReq(r: ResourceBox, f: Throwable => F[Either[String, List[Directive]]]): F[Either[String, List[Directive]]] =
        r.R.load(Required(r.resource)).attempt.flatMap(_.fold(f, a => F.pure(Right(a))))
      def formatError(r: ResourceBox, e: Throwable) =
        s"${r.R.show(r.resource)}: ${e.getMessage}"
      def orAccum(r: ResourceBox, t: F[Either[String, List[Directive]]]) =
        loadReq(r, e1 => t.map(_.leftMap(e2 => s"\n${formatError(r, e1)}$e2")))
      path.worth.toVector.foldRight[F[Either[String, List[Directive]]]](
        if (path.isRequired)
          F.pure(Left(s"\nKnobs failed to load ${path.worth.show}"))
        else
          F.pure(Right(List[Directive]()))
      )(orAccum).flatMap(_.fold(
        e => F.raiseError(ConfigError(path.worth, e)),
        F.pure(_)
      ))
    }
    override def show(r: FallbackChain) = {
      r.toVector.map(_.show).mkString(" or ")
    }
  }

  /**
   * Returns a resource that tries `r1`, and if it fails, falls back to `r2`
   */
  def or[R1: Resource, R2: Resource](r1: R1, r2: R2): FallbackChain =
    NonEmptyVector(box(r1), Vector(box(r2)))

  implicit class ResourceOps[R:Resource](r: R) {
    def or[R2:Resource](r2: R2) = Resource.or(r, r2)

    def resolve(child: String): R = implicitly[Resource[R]].resolve(r, child)

    def required: KnobsResource = Required(box(r))

    def optional: KnobsResource = Optional(box(r))
  }
}

