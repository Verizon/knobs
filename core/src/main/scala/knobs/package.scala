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
import scalaz._
import scalaz.concurrent.Task
import scalaz.syntax.foldable._
import scalaz.syntax.traverse._
import scalaz.syntax.monad._
import scalaz.std.list._
import scalaz.stream._
import scalaz.stream.merge.mergeN
import java.util.concurrent.ExecutorService

package object knobs {
  import Resource._
  import knobs.compatibility._

  private [knobs] type Loaded = Map[KnobsResource, (List[Directive], Process[Task, Unit])]

  /** The name of a configuration property */
  type Name = String

  /** A pure configuration environment map, detached from its sources */
  type Env = Map[Name, CfgValue]

  /** A path-like string */
  type Path = String

  /** exists r. Resource r ⇒ Required r | Optional r */
  type KnobsResource = Worth[ResourceBox]

  /**
    * A change handler takes the `Name` of the property that changed,
    * the value of that property (or `None` if the property was removed),
    * and produces a `Task` to perform in reaction to that change.
    */
  type ChangeHandler = (Name, Option[CfgValue]) => Task[Unit]

  private val P = ConfigParser
  import P.ParserOps

  /**
   * Create a `MutableConfig` from the contents of the given resources. Throws an
   * exception on error, such as if files do not exist or contain errors.
   *
   * File names have any environment variables expanded prior to the
   * first time they are opened, so you can specify a file name such as
   * `"$(HOME)/myapp.cfg"`.
   */
  def load(files: List[KnobsResource],
           pool: ExecutorService = Resource.notificationPool): Task[MutableConfig] =
    loadp(files.map(f => ("", f)), pool).map(MutableConfig("", _))

  /**
   * Create an immutable `Config` from the contents of the named files. Throws an
   * exception on error, such as if files do not exist or contain errors.
   *
   * File names have any environment variables expanded prior to the
   * first time they are opened, so you can specify a file name such as
   * `"$(HOME)/myapp.cfg"`.
   */
  def loadImmutable(files: List[KnobsResource],
                    pool: ExecutorService = Resource.notificationPool): Task[Config] =
    load(files.map(_.map {
      case WatchBox(b, w) => ResourceBox(b)(w)
      case x => x
    }), pool).flatMap(_.immutable)

  /**
   * Create a `MutableConfig` from the contents of the named files, placing them
   * into named prefixes.
   */
  def loadGroups(files: List[(Name, KnobsResource)],
                 pool: ExecutorService = Resource.notificationPool): Task[MutableConfig] =
    loadp(files, pool).map(MutableConfig("", _))

  private [knobs] def loadFiles(paths: List[KnobsResource]): Task[Loaded] = {
    def go(seen: Loaded, path: KnobsResource): Task[Loaded] = {
      def notSeen(n: KnobsResource): Boolean = seen.get(n).isEmpty
      for {
        p <- loadOne(path)
        (ds, _) = p
        seenp = seen + (path -> p)
        box = path.worth
        r <- Foldable[List].foldLeftM(importsOf(box.resource, ds)(box.R).
          filter(notSeen), seenp)(go)
      } yield r
    }
    Foldable[List].foldLeftM(paths, Map():Loaded)(go)
  }

  private [knobs] def loadp(
    paths: List[(Name, KnobsResource)],
    pool: ExecutorService = Resource.notificationPool): Task[BaseConfig] =
      for {
        loaded <- loadFiles(paths.map(_._2))
        p <- IORef(paths)
        m <- flatten(paths, loaded).flatMap(IORef(_))
        s <- IORef(Map[Pattern, List[ChangeHandler]]())
        bc = BaseConfig(paths = p, cfgMap = m, subs = s)
        ticks = mergeN(Process.emitAll(loaded.values.map(_._2).toSeq))
        _ <- Task(ticks.evalMap(_ => bc.reload).run.unsafePerformAsync(_.fold(_ => (), _ => ())))(pool)
      } yield bc

  private [knobs] def addDot(p: String): String =
    if (p.isEmpty || p.endsWith(".")) p else p + "."

  private [knobs] def flatten(roots: List[(Name, KnobsResource)], files: Loaded): Task[Env] = {
    def doResource(m: Env, nwp: (Name, KnobsResource)) = {
      val (pfx, f) = nwp
      val box = f.worth
      files.get(f) match {
        case None => Task.now(m)
        case Some((ds, _)) =>
          Foldable[List].foldLeftM(ds, m)(directive(pfx, box.resource, _, _)(box.R))
      }
    }
    def directive[R: Resource](p: Name, f: R, m: Env, d: Directive): Task[Env] = {
      val pfx = addDot(p)
      d match {
        case Bind(name, CfgText(value)) => for {
          v <- interpolate(f, value, m)
        } yield m + ((pfx + name) -> CfgText(v))
        case Bind(name, value) =>
          Task.now(m + ((pfx + name) -> value))
        case Group(name, xs) =>
          val pfxp = pfx + name + "."
          Foldable[List].foldLeftM(xs, m)(directive(pfxp, f, _, _))
        case Import(path) =>
          val fp = f resolve path
          files.get(fp.required) match {
            case Some((ds, _)) => Foldable[List].foldLeftM(ds, m)(directive(pfx, fp, _, _))
            case _ => Task.now(m)
          }
      }
    }
    Foldable[List].foldLeftM(roots, Map():Env)(doResource)
  }

  private [knobs] def interpolate[R:Resource](f: R, s: String, env: Env): Task[String] = {
    def interpret(i: Interpolation): Task[String] = i match {
      case Literal(x) => Task.now(x)
      case Interpolate(name) => env.get(name) match {
        case Some(CfgText(x)) => Task.now(x)
        case Some(CfgNumber(r)) =>
          if (r % 1 == 0) Task.now(r.toInt.toString)
          else Task.now(r.toString)
        case Some(x) =>
          Task.fail(new Exception(s"type error: $name must be a number or a string"))
        case _ => for {
          // added because lots of sys-admins think software is case unaware. Doh!
          e <- Task.delay(sys.props.get(name) orElse sys.env.get(name) orElse sys.env.get(name.toLowerCase))
          r <- e.map(Task.now).getOrElse(
            Task.fail(ConfigError(f, s"no such variable $name")))
        } yield r
      }
    }
    if (s contains "$") P.interp.parse(s).fold(
      e => Task.fail(new ConfigError(f, e)),
      xs => xs.traverse(interpret).map(_.mkString)
    ) else Task.now(s)
  }

  /** Get all the imports in the given list of directives, relative to the given path */
  def importsOf[R:Resource](path: R, d: List[Directive]): List[KnobsResource] =
    resolveImports(path, d).map(_.required)

  /** Get all the imports in the given list of directives, relative to the given path */
  def resolveImports[R:Resource](path: R, d: List[Directive]): List[R] =
    d match {
      case Import(ref) :: xs => (path resolve ref) :: resolveImports(path, xs)
      case Group(_, ys) :: xs => resolveImports(path, ys) ++ resolveImports(path, xs)
      case _ :: xs => resolveImports(path, xs)
      case _ => Nil
    }

  /**
   * Get all the imports in the given list of directive, recursively resolving
   * imports relative to the given path by loading them.
   */
  def recursiveImports[R:Resource](path: R, d: List[Directive]): Task[List[R]] =
    resolveImports(path, d).traverse(r =>
      implicitly[Resource[R]].load(Required(r)).flatMap(dds =>
        recursiveImports(r, dds))).map(_.flatten)

  private [knobs] def loadOne(path: KnobsResource): Task[(List[Directive], Process[Task, Unit])] = {
    val box = path.worth
    val r: box.R = box.resource
    box.watchable match {
      case Some(ev) => ev.watch(path.map(_ => r))
      case _ => box.R.load(path.map[box.R](_ => r)).map(x => (x, Process.halt))
    }
  }

  private [knobs] def notifySubscribers(
    before: Map[Name, CfgValue],
    after: Map[Name, CfgValue],
    subs: Map[Pattern, List[ChangeHandler]]): Task[Unit] = {
    val changedOrGone = before.foldLeft(List[(Name, Option[CfgValue])]()) {
      case (nvs, (n, v)) => (after get n) match {
        case Some(vp) => if (v != vp) (n, Some(vp)) :: nvs else nvs
        case _ => (n, None) :: nvs
      }
    }
    val news = after.foldLeft(List[(Name, CfgValue)]()) {
      case (nvs, (n, v)) => (before get n) match {
        case None => (n, v) :: nvs
        case _ => nvs
      }
    }
    def notify(p: Pattern, n: Name, v: Option[CfgValue], a: ChangeHandler): Task[Unit] =
      a(n, v).attempt.flatMap {
        case -\/(e) =>
          Task.fail(new Exception(s"A ChangeHandler threw an exception for ${(p, n)}", e))
        case _ => Task.now(())
      }

    subs.foldLeft(Task.now(())) {
      case (next, (p@Exact(n), acts)) => for {
        _ <- next
        v = after get n
        _ <- when(before.get(n) != v) {
          Foldable[List].traverse_(acts)(notify(p, n, v, _))
        }
      } yield ()
      case (next, (p@Prefix(n), acts)) =>
        def matching[A](xs: List[(Name, A)]) = xs.filter(_._1.startsWith(n))
        for {
          _ <- next
          _ <- Foldable[List].traverse_(matching(news)) {
            case (np, v) => Foldable[List].traverse_(acts)(notify(p, np, Some(v), _))
          }
          _ <- Foldable[List].traverse_(matching(changedOrGone)) {
            case (np, v) => Foldable[List].traverse_(acts)(notify(p, np, v, _))
          }
      } yield ()
    }
  }

  import language.higherKinds
  // TODO: Add this to Scalaz
  def when[M[_]:Monad](b: Boolean)(m: M[Unit]) =
    if (b) m else implicitly[Monad[M]].pure(())

}

