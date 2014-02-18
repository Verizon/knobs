import scalaz._
import scalaz.concurrent.Task
import scalaz.syntax.foldable._
import scalaz.syntax.traverse._
import scalaz.syntax.monad._
import scalaz.std.list._

package object knobs {
  private [knobs] type Loaded = Map[Worth[Resource], List[Directive]]

  /** The name of a configuration property */
  type Name = String

  /** A pure configuration environment map, detached from its sources */
  type Env = Map[Name, CfgValue]

  type Path = String

  /**
    * A change handler takes the `Name` of the property that changed,
    * the value of that property (or `None` if the property was removed),
    * and produces a `Task` to perform in reaction to that change.
    */
  type ChangeHandler = (Name, Option[CfgValue]) => Task[Unit]

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
   * into named prefixes.
   */
  def loadGroups(files: List[(Name, Worth[Resource])]): Task[Config] =
    loadp(files).map(Config("", _))

  def loadSystemProperties: Task[Config] = for {
    ps <- Task(sys.props.toMap.map {
      case (k, v) => k -> P.value.parseOnly(v).fold(_ => CfgText(v), a => a)
    })
    props <- IORef(ps)
    paths <- IORef(List[(Name, Worth[Resource])]())
    subs <- IORef(Map[Pattern, List[ChangeHandler]]())
  } yield Config("", BaseConfig(paths, props, subs))

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
    p <- IORef(paths)
    m <- flatten(paths, ds).flatMap(IORef(_))
    s <- IORef(Map[Pattern, List[ChangeHandler]]())
  } yield BaseConfig(paths = p, cfgMap = m, subs = s)

  def addDot(p: String): String = if (p.isEmpty || p.endsWith(".")) p else p + "."

  def flatten(roots: List[(Name, Worth[Resource])], files: Loaded): Task[Env] = {
    def doResource(m: Env, nwp: (Name, Worth[Resource])) = {
      val (pfx, f) = nwp
      files.get(f) match {
        case None => Task.now(m)
        case Some(ds) => Foldable[List].foldLeftM(ds, m)(directive(pfx, f.worth, _, _))
      }
    }
    def directive(p: Name, f: Resource, m: Env, d: Directive): Task[Env] = {
      val pfx = addDot(p)
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

  def notifySubscribers(before: Map[Name, CfgValue],
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
        case _ => Task(())
      }

    subs.foldLeft(Task(())) {
      case (next, (p@Exact(n), acts)) =>
        val v = after get n
        when(before.get(n) != v) {
          Foldable[List].traverse_(acts)(notify(p, n, v, _))
        }.flatMap(_ => next)
      case (next, (p@Prefix(n), acts)) =>
        def matching[A](xs: List[(Name, A)]) = xs.filter(_._1.startsWith(n))
          Foldable[List].traverse_(matching(news)) {
            case (np, v) => Foldable[List].traverse_(acts)(notify(p, np, Some(v), _))
          } >>
          Foldable[List].traverse_(matching(changedOrGone)) {
            case (np, v) => Foldable[List].traverse_(acts)(notify(p, np, v, _))
          }
    }
  }

  import language.higherKinds
  // TODO: Add this to Scalaz
  def when[M[_]:Monad](b: Boolean)(m: M[Unit]) =
    if (b) m else implicitly[Monad[M]].pure(())
}

