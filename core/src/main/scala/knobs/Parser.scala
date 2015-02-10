package knobs

import language.higherKinds._

import scalaz._
import scalaz.syntax.foldable._
import scalaz.syntax.traverse._
import scalaz.std.list._
import scalaz.std.option._
import scala.concurrent.duration.Duration

object ConfigParser {
  val P = new scalaparsers.Parsing[Unit] {}
  import P._

  implicit class ParserOps[A](p: Parser[A]) {
    def parse[A](s: String) = runParser(p, s) match {
      case Left(e) => \/.left(e.pretty.toString)
      case Right((_, r)) => \/.right(r)
    }
  }

  /** Top-level parser of configuration files */
  lazy val topLevel: Parser[List[Directive]] =
    (directives << skipLWS << realEOF) scope "configuration"

  /** Parser of configuration files that don't support import directives */
  lazy val sansImport: Parser[List[Directive]] = {
    val d = (skipLWS >> choice(bindDirective, groupDirective) << skipHWS)
    d.map2(attempt(newline >> d).many)(_ :: _)
  }

  lazy val directives: Parser[List[Directive]] =
    directive.map2(attempt(newline >> directive).many)(_ :: _)

  lazy val directive: Parser[Directive] =
    (skipLWS >> choice(importDirective, bindDirective, groupDirective) << skipHWS) scope
      "directive"

  lazy val importDirective =
    word("import") >> skipLWS >> stringLiteral.map(Import(_)) scope "import directive"

  lazy val bindDirective = {
    attempt(ident.sepBy1('.') << skipLWS << '=' << skipLWS).map2(value) { (x, v) =>
      val xs = x.reverse
      xs.tail.foldLeft(Bind(xs.head, v):Directive)((d, g) => Group(g, List(d)))
    }
  } scope "bind directive"

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
    duration.attempt,
    scientific.map(d => CfgNumber(d.toDouble)),
    list.map(CfgList(_))
  ) scope "value"

  def isChar(b: Boolean, c: Char) =
    if (b) Some(false) else if (c == '"') None else Some(c == '\\')

//  lazy val string: Parser[String] =
//    (ch('\"') >> satisfy(_ != '\"').many.map(_.mkString) << ch('\"')) scope "string literal"

  lazy val stringChar = stringLetter.map(Some(_)) | stringEscape

  private lazy val stringLetter = satisfy(c => (c != '"') && (c != '\\') && (c > '\026'))

  private lazy val stringEscape = ch('\\') >> {
    (satisfy(_.isWhitespace).skipSome >> ch('\\')).as(None) |
    escapeCode.map(Some(_))
  }

  private val charEscMagic: Map[Char, Char] = "bfnrt\\\"'".zip("\b\f\n\r\t\\\"'").toMap

  private lazy val escapeCode =
    choice(charEscMagic.toSeq.map { case (c,d) => ch(c) as d } :_*) scope "escape code"

  lazy val string: Parser[String] = stringChar.many.between('"','"').map(
    _.sequence[Option,Char].getOrElse(List()).mkString) scope "string literal"

  lazy val list: Parser[List[CfgValue]] =
    (ch('[') >> (skipLWS >> value << skipLWS).sepBy(ch(',') << skipLWS) << ']') scope "list"

  def isCont(c: Char) = Character.isLetterOrDigit(c) || c == '_' || c == '-'

  lazy val signedInt: Parser[BigInt] =
    (ch('-') >> decimal).map(- _) | (ch('+') >> decimal) | decimal

  lazy val scientific: Parser[BigDecimal] = (for {
    positive <- satisfy(c => c == '-' || c == '+').map(_ == '+') | unit(true)
    n <- decimal
    s <- (satisfy(_ == '.') >> takeWhile(_.isDigit).map(f =>
      BigDecimal(n + "." + f.mkString))) | unit(BigDecimal(n))
    sCoeff = if (positive) s else (- s)
    r <- satisfy(c => c == 'e' || c == 'E') >>
         signedInt.flatMap(x =>
           if (x > Int.MaxValue) fail[Parser](s"Exponent too large: $x")
           else unit(s * BigDecimal(10).pow(x.toInt))) | unit(sCoeff)
  } yield r) scope "numeric literal"

  private def addDigit(a: BigInt, c: Char) = a * 10 + (c - 48)

  lazy val digit = satisfy(_.isDigit) scope "digit"

  lazy val decimal: Parser[BigInt] =
    digit.some.map(_.foldLeft(BigInt(0))(addDigit))

  lazy val duration: Parser[CfgValue] = (for {
    d <- (scientific << whitespace.skipOptional)
    x <- takeWhile(_.isLetter).map(_.mkString).scope("time unit")
    r <- \/.fromTryCatchNonFatal(Duration.create(1, x)).fold(
      e => fail(e.getMessage),
      o => unit(CfgDuration(o * d.toDouble)))
  } yield r).scope("duration")

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
