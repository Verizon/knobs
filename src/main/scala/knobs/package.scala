import scalaz._
import scalaz.concurrent.Task
import scalaz.std.list._
import scalaz.syntax.foldable._
import scalaz.syntax.traverse._

package object knobs {
  type Name = String
  type Loaded = Map[Worth[Resource], List[Directive]]
  type Env = Map[Name, CfgValue]
  type Path = String

  private val P = ConfigParser

  /**
   * Create a `Config` from the contents of the named files. Throws an
   * exception on error, such as if files do not exist or contain errors.
   *
   * File names have any environment variables expanded prior to the
   * first time they are opened, so you can speficy a file name such as
   * `"$(HOME)/myapp.cfg"`.
   */
  def load(files: List[Worth[Resource]]): Task[Config] =
    loadp(files.map(f => ("", f))).map(Config("", _))

  /**
   * Create a `Config` from the contents of the named files, placing them
   * into named prefixes. If a prefix is non-empty, it should end in a
   * dot.
   */
  def loadGroups(files: List[(Name, Worth[Resource])]): Task[Config] =
    loadp(files).map(Config("", _))

  def loadSystemProperties: Task[Config] = Task {
    val props = sys.props.toMap.map {
      case (k, v) => k -> P.value.parseOnly(v).fold(_ => CfgText(v), a => a)
    }
    Config("", BaseConfig(Nil, props))
  }

  def loadFiles(paths: List[Worth[Resource]]): Task[Loaded] = {
    def go(seen: Loaded, path: Worth[Resource]): Task[Loaded] = {
      def notSeen(n: Worth[Resource]): Boolean = seen.get(n).isEmpty
      for {
        ds <- loadOne(path)
        seenp = seen + (path -> ds)
        r <- Foldable[List].foldLeftM(importsOf(path.worth, ds).filter(notSeen), seenp)(go)
      } yield r
    }
    Foldable[List].foldLeftM(paths, Map():Loaded)(go)
  }

  def loadp(paths: List[(Name, Worth[Resource])]): Task[BaseConfig] = for {
    ds <- loadFiles(paths.map(_._2))
    r <- flatten(paths, ds)
  } yield BaseConfig(paths, r)

  def flatten(roots: List[(Name, Worth[Resource])], files: Loaded): Task[Env] = {
    def doResource(m: Env, nwp: (Name, Worth[Resource])) = {
      val (pfx, f) = nwp
      files.get(f) match {
        case None => Task.now(m)
        case Some(ds) => Foldable[List].foldLeftM(ds, m)(directive(pfx, f.worth, _, _))
      }
    }
    def directive(pfx: Name, f: Resource, m: Env, d: Directive): Task[Env] =
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
          val fp = f resolve path
          files.get(Required(fp)) match {
            case Some(ds) => Foldable[List].foldLeftM(ds, m)(directive(pfx, fp, _, _))
            case _ => Task.now(m)
          }
      }
    Foldable[List].foldLeftM(roots, Map():Env)(doResource)
  }

  def interpolate(s: String, env: Env): Task[String] = {
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
          e <- Task(sys.props.get(name) orElse sys.env.get(name))
          r <- e.map(Task.now).getOrElse(
            Task.fail(ConfigError("", s"no such variable $name")))
        } yield r
      }
    }
    if (s contains "$") P.interp.parseOnly(s).fold(
      e => Task.fail(new ConfigError("", e)),
      xs => xs.traverse(interpret).map(_.mkString)
    ) else Task.now(s)
  }

  def importsOf(path: Resource, d: List[Directive]): List[Worth[Resource]] = d match {
    case Import(ref) :: xs => Required(path resolve ref) :: importsOf(path, xs)
    case Group(_, ys) :: xs => importsOf(path, ys) ++ importsOf(path, xs)
    case _ :: xs => importsOf(path, xs)
    case _ => Nil
  }

  def readFile(path: Resource) = path match {
    case URIResource(uri) => Task(scala.io.Source.fromFile(uri).mkString)
    case FileResource(f) => Task(scala.io.Source.fromFile(f).mkString)
    case ClassPathResource(r) =>
      Task(getClass.getClassLoader.getResource(r)) flatMap { x =>
        if (x == null) Task.fail(new java.io.FileNotFoundException(r + " (on classpath)"))
        else Task(scala.io.Source.fromFile(x.toURI).mkString)
      }
  }

  def loadOne(path: Worth[Resource]): Task[List[Directive]] = for {
    es <- readFile(path.worth).attempt
    r <- es.fold(ex => path match {
                   case Required(_) => Task.fail(ex)
                   case _ => Task.now(Nil)
                 },
                 s => for {
                   p <- Task.delay(P.topLevel.parseOnly(s)).attempt.flatMap {
                     case -\/(ConfigError(_, err)) =>
                       Task.fail(ConfigError(path.worth.show, err))
                     case -\/(e) => Task.fail(e)
                     case \/-(a) => Task.now(a)
                   }
                   r <- p.fold(err => Task.fail(ConfigError(path.worth.show, err)),
                               ds => Task.now(ds))
                 } yield r)
  } yield r
}

