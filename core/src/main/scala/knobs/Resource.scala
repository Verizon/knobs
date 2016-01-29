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

import java.net.{URI, URL}
import java.io.File
import java.nio.file.{ FileSystems, Path => P, WatchEvent }
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.util.concurrent.{ Executors, ThreadFactory }
import scala.collection.JavaConverters._
import scalaz._
import scalaz.syntax.show._
import scalaz.concurrent.Task
import scalaz.stream._
import scalaz.stream.async.mutable.Topic
import scalaz.stream.merge.mergeN

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
   * Loads a resource, returning a list of config directives in `Task`.
   */
  def load(path: Worth[R]): Task[List[Directive]]
}

trait Watchable[R] extends Resource[R] {
  def watch(path: Worth[R]): Task[(List[Directive], Process[Task, Unit])]
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
    new WatchBox(r, implicitly[Watchable[R]])
}

object ResourceBox {
  def apply[R:Resource](r: R) = Resource.box(r)

  implicit def resourceBoxShow: Show[ResourceBox] = new Show[ResourceBox] {
    override def shows(b: ResourceBox): String = b.R.shows(b.resource)
  }
}


object FileResource {
  /**
   * Creates a new resource that loads a configuration from a file.
   * Optionally creates a process to watch changes to the file and
   * reload any `MutableConfig` if it has changed.
   */
  def apply(f: File, watched: Boolean = true): ResourceBox = Watched(f.getCanonicalFile)

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
  type FallbackChain = OneAnd[Vector, ResourceBox]
  def pool(name: String) = Executors.newCachedThreadPool(new ThreadFactory {
    def newThread(r: Runnable) = {
      val t = Executors.defaultThreadFactory.newThread(r)
      t.setDaemon(true)
      t.setName(name)
      t
    }
  })

  // Thread pool for asynchronously watching config files
  val watchPool = pool("knobs-watch-service-pool")

  // Thread pool for notifying subcribers of changes to mutable configs
  val notificationPool = pool("knobs-notification-pool")

  private val watchService: WatchService = FileSystems.getDefault.newWatchService
  private val watchProcess: Process[Task, WatchEvent[_]] =
    Process.eval(Task(watchService.take)(watchPool)).flatMap(s =>
      Process.emitAll(s.pollEvents.asScala)).repeat
  private val watchTopic: Topic[WatchEvent[_]] =
    async.topic(watchProcess)(scalaz.concurrent.Strategy.Executor(watchPool))

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
   * `load` is a `Task` that does the work of turning the resource into a `String`
   * that gets parsed by the `ConfigParser`.
   */
  def loadFile[R:Resource](path: Worth[R], load: Task[String]): Task[List[Directive]] = {
    import ConfigParser.ParserOps
    for {
      es <- load.attempt
      r <- es.fold(ex =>
        path match {
          case Required(_) => Task.fail(ex)
          case _ => Task.now(Nil)
        }, s => for {
          p <- Task.delay(ConfigParser.topLevel.parse(s)).attempt.flatMap {
            case -\/(ConfigError(_, err)) =>
              Task.fail(ConfigError(path.worth, err))
            case -\/(e) => Task.fail(e)
            case \/-(a) => Task.now(a)
          }
          r <- p.fold(err => Task.fail(ConfigError(path.worth, err)),
                      ds => Task.now(ds))
        } yield r)
    } yield r
  }

  def failIfNone[A](a: Option[A], msg: String): Task[A] =
    a.map(Task.now).getOrElse(Task.fail(new RuntimeException(msg)))

  def watchEvent(path: P): Task[Process[Task, WatchEvent[_]]] = {
    val dir =
      failIfNone(Option(path.getParent),
                 s"File $path has no parent directory. Please provide a canonical file name.")
    val file =
      failIfNone(Option(path.getFileName),
                 s"Path $path has no file name.")

    for {
      d <- dir
      _ <- Task.delay(d.register(watchService, ENTRY_MODIFY))
      f <- file
    } yield watchTopic.subscribe.filter(_.context == f)
  }

  implicit def fileResource: Watchable[File] = new Watchable[File] {
    import scalaz.syntax.traverse._
    import scalaz.std.list._
    def resolve(r: File, child: String): File =
      new File(resolveName(r.getPath, child))
    def load(path: Worth[File]) =
      loadFile(path, Task.delay(scala.io.Source.fromFile(path.worth).mkString))
    override def shows(r: File) = r.toString
    def watch(path: Worth[File]) = for {
      ds <- load(path)
      rs <- recursiveImports(path.worth, ds)
      es <- (path.worth +: rs).traverse(f => watchEvent(f.toPath))
    } yield (ds, mergeN(Process.emitAll(es.map(_.map(_ => ())))))
  }

  implicit def uriResource: Resource[URI] = new Resource[URI] {
    def resolve(r: URI, child: String): URI = r resolve new URI(child)
    def load(path: Worth[URI]) =
      loadFile(path, Task.delay(scala.io.Source.fromURL(path.worth.toURL).mkString + "\n"))
    override def shows(r: URI) = r.toString
  }

  final case class ClassPath(s: String, loader: ClassLoader)

  implicit def classPathResource: Resource[ClassPath] = new Resource[ClassPath] {
    def resolve(r: ClassPath, child: String) =
      ClassPath(resolveName(r.s, child), r.loader)
    def load(path: Worth[ClassPath]) = {
      val r = path.worth
      loadFile(path, Task.delay(r.loader.getResourceAsStream(r.s)) flatMap { x =>
        if (x == null) Task.fail(new java.io.FileNotFoundException(r.s + " not found on classpath"))
        else Task.delay(scala.io.Source.fromInputStream(x).mkString)
      })
    }
    override def shows(r: ClassPath) = {
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
    def load(path: Worth[Pattern]) = {
      val pat = path.worth
      for {
        ds <- Task.delay(sys.props.toMap.filterKeys(pat matches _).map {
                   case (k, v) => Bind(k, ConfigParser.value.parse(v).toOption.getOrElse(CfgText(v)))
                 })
        r <- (ds.isEmpty, path) match {
          case (true, Required(_)) =>
            Task.fail(new ConfigError(path.worth, s"Required system properties $pat not present."))
          case _ => Task.now(ds.toList)
        }
      } yield r
    }
    override def shows(r: Pattern): String = s"System properties $r.*"
  }

  implicit def fallbackResource: Resource[FallbackChain] = new Resource[FallbackChain] {
    def resolve(p: FallbackChain, child: String) = {
      val OneAnd(a, as) = p
      OneAnd(box(a.R.resolve(a.resource, child))(a.R), as.map(b =>
        box(b.R.resolve(b.resource, child))(b.R)
      ))
    }
    import \/._
    def load(path: Worth[FallbackChain]) = {
      def loadReq(r: ResourceBox, f: Throwable => Task[String \/ List[Directive]]) =
        r.R.load(Required(r.resource)).attempt.flatMap(_.fold(f, a => Task.now(right(a))))
      def formatError(r: ResourceBox, e: Throwable) =
        s"${r.R.show(r.resource)}: ${e.getMessage}"
      def orAccum(r: ResourceBox, t: Task[String \/ List[Directive]]) =
        loadReq(r, e1 => t.map(_.leftMap(e2 => s"\n${formatError(r, e1)}$e2")))
      val OneAnd(r, rs) = path.worth
      (r +: rs).foldRight(
        if (path.isRequired)
          Task.now(left(s"\nKnobs failed to load ${path.worth.show}"))
        else
          Task.now(right(List[Directive]()))
      )(orAccum).flatMap(_.fold(
        e => Task.fail(ConfigError(path.worth, e)),
        Task.now(_)
      ))
    }
    override def shows(r: FallbackChain) = {
      val OneAnd(a, as) = r
      (a +: as).map(_.show).mkString(" or ")
    }
  }

  /**
   * Returns a resource that tries `r1`, and if it fails, falls back to `r2`
   */
  def or[R1: Resource, R2: Resource](r1: R1, r2: R2): FallbackChain =
    OneAnd(box(r1), Vector(box(r2)))

  implicit class ResourceOps[R:Resource](r: R) {
    def or[R2:Resource](r2: R2) = Resource.or(r, r2)

    def resolve(child: String): R = implicitly[Resource[R]].resolve(r, child)

    def required: KnobsResource = Required(box(r))

    def optional: KnobsResource = Optional(box(r))
  }
}

