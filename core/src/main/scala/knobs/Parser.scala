package knobs

import language.higherKinds._

import scalaz._
import scalaz.syntax.foldable._
import scalaz.std.list._

object ConfigParser {
  val P = new scalaparsers.Parsing[Unit] {}
  import P._

  implicit class ParserOps[A](p: Parser[A]) {
    def parse[A](s: String) = runParser(p, s) match {
      case Left(e) => \/.left(e.pretty.toString)
      case Right((_, r)) => \/.right(r)
    }
  }

  lazy val topLevel: Parser[List[Directive]] =
    (directives << skipLWS << realEOF) scope "configuration"

  lazy val directives: Parser[List[Directive]] =
    directive.map2(attempt(newline >> directive).many)(_ :: _)

  lazy val directive: Parser[Directive] =
    (skipLWS >> choice(importDirective, bindDirective, groupDirective) << skipHWS) scope
      "directive"

  lazy val importDirective =
    word("import") >> skipLWS >> stringLiteral.map(Import(_)) scope "import directive"

  lazy val bindDirective =
    attempt(ident << skipLWS << '=' << skipLWS).map2(value)(Bind(_, _)) scope "bind directive"

  lazy val groupDirective = { for {
    g <- attempt(ident << skipLWS << '{' << skipLWS)
    ds <- directives << skipLWS << '}'
  } yield Group(g, ds) } scope "group directive"

  // Skip lines, comments, or horizontal white space
  lazy val skipLWS: Parser[Unit] = (newline | comment | whitespace).skipMany

  // Skip comments or horizontal whitespace
  lazy val skipHWS: Parser[Unit] = (comment | whitespace).skipMany

  lazy val newline: Parser[Unit] =
    satisfy(c => c == '\r' || c == '\n').skip scope "newline"

  lazy val whitespace: Parser[Unit] =
    satisfy(c => c.isWhitespace && c != '\r' && c != '\n').skip scope "whitespace"

  lazy val comment: Parser[Unit] =
    { ch('#').attempt >>
      satisfy(c => c != '\r' && c != '\n').skipMany >>
      (newline | realEOF) >>
      unit(()) } scope "comment"

  def takeWhile(p: Char => Boolean): Parser[List[Char]] = satisfy(p).many

  import scalaparsers.Document._
  import scalaparsers.Diagnostic._

  lazy val ident: Parser[Name] = for {
    n <- satisfy(c => Character.isLetter(c)).map2(takeWhile(isCont))(_ +: _)
    _ <- failWhen[Parser](n == "import", s"reserved word ($n) used as an identifier")
  } yield n.mkString

  lazy val value: Parser[CfgValue] = choice(
    word("on") >> unit(CfgBool(true)),
    word("off") >> unit(CfgBool(false)),
    word("true") >> unit(CfgBool(true)),
    word("false") >> unit(CfgBool(false)),
    string.map(CfgText(_)),
    scientific.map(d => CfgNumber(d.toDouble)) scope "numeric literal",
    (ch('[') >> (skipLWS >> value << skipLWS).sepBy(ch(',') << skipLWS) << ']').map(CfgList(_))
      scope "list"
  ) scope "value"

  def isChar(b: Boolean, c: Char) =
    if (b) Some(false) else if (c == '"') None else Some(c == '\\')

  lazy val string: Parser[String] =
    ch('\"') >> satisfy(_ != '\"').many.map(_.mkString) << ch('\"')

  def isCont(c: Char) = Character.isLetterOrDigit(c) || c == '_' || c == '-'

  lazy val signedInt: Parser[BigInt] =
    (ch('-') >> decimal).map(- _) | ch('+') >> decimal | decimal

  lazy val scientific: Parser[BigDecimal] = for {
    positive <- satisfy(c => c == '-' || c == '+').map(_ == '+') | unit(true)
    n <- decimal
    s <- (satisfy(_ == '.') >> takeWhile(_.isDigit).map(f =>
      BigDecimal(n + "." + f.mkString))) | unit(BigDecimal(n))
    sCoeff = if (positive) s else (- s)
    r <- satisfy(c => c == 'e' || c == 'E') >>
         signedInt.flatMap(x =>
           if (x > Int.MaxValue) fail[Parser](s"Exponent too large: $x")
           else unit(s * BigDecimal(10).pow(x.toInt))) | unit(sCoeff)
  } yield r

  private def addDigit(a: BigInt, c: Char) = a * 10 + (c - 48)

  lazy val decimal: Parser[BigInt] =
    satisfy(_.isDigit).some.map(_.foldLeft(BigInt(0))(addDigit))

  /**
   * Parse a string interpolation spec. The sequence `$$` is treated as a single
   * `$` character. The sequence `$(` begins a section to be interpolated,
   * and `)` ends it.
   */
  lazy val interp: Parser[List[Interpolation]] = {
    def p(acc: List[Interpolation]): Parser[List[Interpolation]] = for {
      h <- takeWhile(_ != '$').map(x => Literal(x.mkString))
      rest = {
        def cont(x: Interpolation): Parser[List[Interpolation]] = p(x :: h :: acc)
        for {
          c <- ch('$') >> satisfy(c => c == '$' || c == '(')
          r <- if (c == '$') cont(Literal("$"))
               else (satisfy(_ != ')').some << ')').flatMap(x => cont(Interpolate(x.mkString)))
        } yield r
      }
      r <- rest | unit(h :: acc)
    } yield r
    p(Nil).map(_.reverse)
  }

  import scalaparsers.ParseState
  import scalaparsers.Supply
  import scalaparsers.Pos

  def apply(input: String, fileName: Path) =
    runParser(topLevel, input, fileName)

  def runParser[A](p: Parser[A], input: String, fileName: Path = "") =
    p.run(ParseState(
      loc = Pos.start(fileName, input),
      input = input,
      s = (),
      layoutStack = List()), Supply.create)
}
