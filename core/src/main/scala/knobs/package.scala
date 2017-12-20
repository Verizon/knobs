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
import java.util.concurrent.ExecutorService

import cats._
import cats.effect._
import cats.implicits._
import fs2.Stream
import scala.concurrent.ExecutionContext

package object knobs {
  import Resource._

  private [knobs] type Loaded[F[_]] = Map[KnobsResource, (List[Directive], Stream[F, Unit])]

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
    * and produces a `IO` to perform in reaction to that change.
    */
  type ChangeHandler[F[_]] = (Name, Option[CfgValue]) => F[Unit]

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
  def load[F[_]: Effect](files: List[KnobsResource],
           pool: ExecutionContext = Resource.notificationPool): F[MutableConfig[F]] =
    loadp(files.map(f => ("", f)), pool).map(MutableConfig("", _))

  /**
   * Create an immutable `Config` from the contents of the named files. Throws an
   * exception on error, such as if files do not exist or contain errors.
   *
   * File names have any environment variables expanded prior to the
   * first time they are opened, so you can specify a file name such as
   * `"$(HOME)/myapp.cfg"`.
   */
  def loadImmutable[F[_]: Effect](files: List[KnobsResource],
                    pool: ExecutionContext = Resource.notificationPool): F[Config] =
    load(files.map(_.map {
      case WatchBox(b, w) => ResourceBox(b)(w)
      case x => x
    }), pool).flatMap(_.immutable)

  /**
   * Create a `MutableConfig` from the contents of the named files, placing them
   * into named prefixes.
   */
  def loadGroups[F[_]: Effect](files: List[(Name, KnobsResource)],
                 pool: ExecutionContext = Resource.notificationPool): F[MutableConfig[F]] =
    loadp(files, pool).map(MutableConfig("", _))

  private [knobs] def loadFiles[F[_]: Effect](paths: List[KnobsResource]): F[Loaded[F]] = {
    def go(seen: Loaded[F], path: KnobsResource): F[Loaded[F]] = {
      def notSeen(n: KnobsResource): Boolean = seen.get(n).isEmpty
      for {
        p <- loadOne(path)
        (ds, _) = p
        seenp = seen + (path -> p)
        box = path.worth
        imports <- importsOfM(box.resource, ds)(Sync[F], box.R)
        r <- imports.filter(notSeen).foldLeftM(seenp)(go)
      } yield r
    }
    paths.foldLeftM(Map(): Loaded[F])(go)
  }

  private [knobs] def loadp[F[_]](
    paths: List[(Name, KnobsResource)],
    pool: ExecutionContext = Resource.notificationPool)(implicit F: Effect[F]): F[BaseConfig[F]] = {
      implicit val implicitPool = pool
      for {
        loaded <- loadFiles(paths.map(_._2))
        p <- IORef(paths)
        m <- flatten(paths, loaded).flatMap(IORef(_))
        s <- IORef(Map[Pattern, List[ChangeHandler[F]]]())
        bc = BaseConfig(paths = p, cfgMap = m, subs = s)
        ticks = Stream.emits(loaded.values.map(_._2).toSeq).joinUnbounded
        _ <- F.delay(F.runAsync(ticks.evalMap(_ => bc.reload).run)(_ => IO.unit).unsafeRunSync)
      } yield bc
    }

  private [knobs] def addDot(p: String): String =
    if (p.isEmpty || p.endsWith(".")) p else p + "."

  private [knobs] def flatten[F[_]](roots: List[(Name, KnobsResource)], files: Loaded[F])(implicit F: Sync[F]): F[Env] = {
    def doResource(m: Env, nwp: (Name, KnobsResource)) = {
      val (pfx, f) = nwp
      val box = f.worth
      files.get(f) match {
        case None => F.pure(m)
        case Some((ds, _)) =>
          ds.foldLeftM(m)(directive(pfx, box.resource, _, _)(box.R))
      }
    }
    def directive[R: Resource](p: Name, f: R, m: Env, d: Directive): F[Env] = {
      val pfx = addDot(p)
      d match {
        case Bind(name, CfgText(value)) => for {
          v <- interpolate(f, value, m)
        } yield m + ((pfx + name) -> CfgText(v))
        case Bind(name, value) =>
          F.pure(m + ((pfx + name) -> value))
        case Group(name, xs) =>
          val pfxp = pfx + name + "."
          xs.foldLeftM(m)(directive(pfxp, f, _, _))
        case Import(path) =>
          interpolateEnv(f, path).map(f.resolve).flatMap { fp =>
            files.get(fp.required) match {
              case Some((ds, _)) => ds.foldLeftM(m)(directive(pfx, fp, _, _))
              case _ => F.pure(m)
            }
          }
      }
    }
    roots.foldLeftM(Map(): Env)(doResource)
  }

  private [knobs] def interpolate[F[_], R:Resource](f: R, s: String, env: Env)(implicit F: Sync[F]): F[String] = {
    def interpret(i: Interpolation): F[String] = i match {
      case Literal(x) => F.pure(x)
      case Interpolate(name) => env.get(name) match {
        case Some(CfgText(x)) => F.pure(x)
        case Some(CfgNumber(r)) =>
          if (r % 1 == 0) F.pure(r.toInt.toString)
          else F.pure(r.toString)
        case Some(x) =>
          F.raiseError(new Exception(s"type error: $name must be a number or a string"))
        case _ => for {
          // added because lots of sys-admins think software is case unaware. Doh!
          e <- F.delay(sys.props.get(name) orElse sys.env.get(name) orElse sys.env.get(name.toLowerCase))
          r <- e.map(F.pure).getOrElse(
            F.raiseError(ConfigError(f, s"no such variable $name")))
        } yield r
      }
    }
    if (s contains "$") P.interp.parse(s).fold(
      e => F.raiseError(new ConfigError(f, e)),
      xs => xs.traverse(interpret).map(_.mkString)
    ) else F.pure(s)
  }

  /** Get all the imports in the given list of directives, relative to the given path */
  @deprecated("Does not support interpolation of environment variables", "4.0.31")
  def importsOf[R:Resource](path: R, d: List[Directive]): List[KnobsResource] =
    resolveImports(path, d).map(_.required)

  private [knobs] def importsOfM[F[_]: Sync, R:Resource](path: R, d: List[Directive]): F[List[KnobsResource]] =
    resolveImportsM(path, d).map(_.map(_.required))

  private [knobs] def interpolateEnv[F[_], R: Resource](f: R, s: String)(implicit F: Sync[F]): F[String] = {
    def interpret(i: Interpolation): F[String] = i match {
      case Literal(x) => F.pure(x)
      case Interpolate(name) =>
        sys.env.get(name)
          // added because lots of sys-admins think software is case unaware. Doh!
          .orElse(sys.env.get(name.toLowerCase))
          .fold[F[String]](F.raiseError(ConfigError(f, s"""No such variable "$name". Only environment variables are interpolated in import directives.""")))(F.pure)
    }
    if (s contains "$") P.interp.parse(s).fold(
      e => F.raiseError(ConfigError(f, e)),
      xs => xs.traverse(interpret).map(_.mkString)
    ) else F.pure(s)
  }

  /** Get all the imports in the given list of directives, relative to the given path */
  @deprecated("Does not support interpolation of environment variables", "4.0.31")
  def resolveImports[R:Resource](path: R, d: List[Directive]): List[R] =
    d match {
      case Import(ref) :: xs => (path resolve ref) :: resolveImports(path, xs)
      case Group(_, ys) :: xs => resolveImports(path, ys) ++ resolveImports(path, xs)
      case _ :: xs => resolveImports(path, xs)
      case _ => Nil
    }

  /** Get all the imports in the given list of directives, relative to the given path */
  private [knobs] def resolveImportsM[F[_], R: Resource](path: R, d: List[Directive])(implicit F: Sync[F]): F[List[R]] =
    d.flatTraverse {
      case Import(ref) => interpolateEnv(path, ref).map(r => List(path.resolve(r)))
      case Group(_, ys) => resolveImportsM(path, ys)
      case _ => F.pure(Nil)
    }

  /**
   * Get all the imports in the given list of directive, recursively resolving
   * imports relative to the given path by loading them.
   */
  def recursiveImports[F[_]: Sync, R:Resource](path: R, d: List[Directive]): F[List[R]] =
    resolveImportsM(path, d).flatMap(_.flatTraverse(r =>
      implicitly[Resource[R]].load(Required(r)).flatMap(dds =>
        recursiveImports(r, dds))))

  private [knobs] def loadOne[F[_]: Effect](path: KnobsResource): F[(List[Directive], Stream[F, Unit])] = {
    val box = path.worth
    val r: box.R = box.resource
    box.watchable match {
      case Some(ev) => ev.watch(path.map(_ => r))
      case _ => box.R.load(path.map[box.R](_ => r)).map(x => (x, Stream.empty.covary[F]))
    }
  }

  private [knobs] def notifySubscribers[F[_]](
    before: Map[Name, CfgValue],
    after: Map[Name, CfgValue],
    subs: Map[Pattern, List[ChangeHandler[F]]])(implicit F: Sync[F]): F[Unit] = {
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
    def notify(p: Pattern, n: Name, v: Option[CfgValue], a: ChangeHandler[F]): F[Unit] =
      a(n, v).attempt.flatMap {
        case Left(e) =>
          F.raiseError(new Exception(s"A ChangeHandler threw an exception for ${(p, n)}", e))
        case _ => F.pure(())
      }

    subs.foldLeft(F.pure(())) {
      case (next, (p@Exact(n), acts)) => for {
        _ <- next
        v = after get n
        _ <- F.whenA(before.get(n) != v) {
          acts.traverse_(notify(p, n, v, _))
        }
      } yield ()
      case (next, (p@Prefix(n), acts)) =>
        def matching[A](xs: List[(Name, A)]) = xs.filter(_._1.startsWith(n))
        for {
          _ <- next
          _ <- matching(news).traverse_ {
            case (np, v) => acts.traverse_(notify(p, np, Some(v), _))
          }
          _ <- matching(changedOrGone).traverse_ {
            case (np, v) => acts.traverse_(notify(p, np, v, _))
          }
      } yield ()
    }
  }
}
