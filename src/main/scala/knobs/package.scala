import scalaz._
import scalaz.concurrent.Task
import scalaz.std.list._
import scalaz.syntax.foldable._
import scalaz.syntax.traverse._

package object knobs {
  type Name = String
  type Path = String
  type FilePath = String
  type Loaded = Map[Worth[Path], List[Directive]]
  type Env = Map[Name, CfgValue]

  private val P = ConfigParser

  private def loadFiles(paths: List[Worth[Path]]): Task[Loaded] = {
    def go(seen: Loaded, path: Worth[Path]): Task[Loaded] = {
      def rewrap(n: Path) = path.map(_ => n)
      def notSeen(n: Worth[Path]): Boolean = seen.get(n).isEmpty
      val wpath = path.worth
      for {
        pathp <- interpolate(wpath, Map()).map(rewrap)
        ds <- loadOne(pathp)
        seenp = seen + (path -> ds)
        r <- Foldable[List].foldLeftM(importsOf(wpath, ds).filter(notSeen), seenp)(go)
      } yield r
    }
    Foldable[List].foldLeftM(paths, Map():Loaded)(go)
  }

  /**
   * Create a `Config` from the contents of the named files. Throws an
   * exception on error, such as if files do not exist or contain errors.
   *
   * File names have any environment variables expanded prior to the
   * first time they are opened, so you can speficy a file name such as
   * `"$(HOME)/myapp.cfg"`.
   */
  def load(files: List[Worth[FilePath]]): Task[Config] =
    loadp(files.map(f => ("", f))).map(Config("", _))

  /**
   * Create a `Config` from the contents of the named files, placing them
   * into named prefixes. If a prefix is non-empty, it should end in a
   * dot.
   */
  def loadGroups(files: List[(Name, Worth[FilePath])]): Task[Config] =
    loadp(files).map(Config("", _))

  private def loadp(paths: List[(Name, Worth[FilePath])]): Task[BaseConfig] = for {
    ds <- loadFiles(paths.map(_._2))
    r <- flatten(paths, ds)
  } yield BaseConfig(paths, r)

  private def flatten(roots: List[(Name, Worth[Path])],
                      files: Loaded): Task[Env] = {
    def doPath(m: Env, nwp: (Name, Worth[Path])) = {
      val (pfx, f) = nwp
      files.get(f) match {
        case None => Task.now(m)
        case Some(ds) => Foldable[List].foldLeftM(ds, m)(directive(pfx, f.worth, _, _))
      }
    }
    def directive(pfx: Name, f: Path, m: Env, d: Directive): Task[Env] =
      d match {
        case Bind(name, CfgText(value)) => for {
          v <- interpolate(value, m)
        } yield m + ((pfx + name) -> CfgText(v))
        case Bind(name, value) =>
          Task.now(m + ((pfx + name) -> value))
        case Group(name, xs) =>
          val pfxp = pfx + name + "."
          Foldable[List].foldLeftM(xs, m)(directive(pfxp, f, _, _))
        case Import(path) =>
          val fp = relativize(f, path)
          files.get(Required(fp)) match {
            case Some(ds) => Foldable[List].foldLeftM(ds, m)(directive(pfx, fp, _, _))
            case _ => Task.now(m)
          }
      }
    Foldable[List].foldLeftM(roots, Map():Env)(doPath)
  }

  private def interpolate(s: String, env: Env): Task[String] = {
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
          e <- Task(sys.env.get(name))
          r <- e.map(Task.now).getOrElse(Task.fail(ConfigError("", s"no such variable $name")))
        } yield r
      }
    }
    if (s contains "$") P.interp.parseOnly(s).fold(
      e => Task.fail(new ConfigError("", e)),
      xs => xs.traverse(interpret).map(_.mkString)
    ) else Task.now(s)
  }

  private def importsOf(path: Path, d: List[Directive]): List[Worth[Path]] = d match {
    case Import(ref) :: xs => Required(relativize(path, ref)) :: importsOf(path, xs)
    case Group(_, ys) :: xs => importsOf(path, ys) ++ importsOf(path, xs)
    case _ :: xs => importsOf(path, xs)
    case _ => Nil
  }

  private def relativize(parent: Path, child: Path): Path =
    if (child.head == '/') child
    else parent.substring(0, parent.lastIndexOf('/')) ++ child

  private def readFile(path: FilePath) =
    Task(scala.io.Source.fromFile(path).mkString)

  private def loadOne(path: Worth[FilePath]): Task[List[Directive]] = for {
    es <- readFile(path.worth).attempt
    r <- es.fold(ex => path match {
                   case Required(_) => Task.fail(ex)
                   case _ => Task.now(Nil)
                 },
                 s => for {
                   p <- Task.delay(P.topLevel.parseOnly(s)).attempt.flatMap {
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

