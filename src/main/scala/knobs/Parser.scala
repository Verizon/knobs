package knobs

import language.higherKinds._

import scalaz._
import scalaz.syntax.foldable._
import scalaz.std.list._

import attoparsec._

object ConfigParser {
  import Parser._
  val P = Applicative[Parser]

  lazy val topLevel: Parser[List[Directive]] =
    directives <~ skipLWS <~ endOfInput

  lazy val directive: Parser[Directive] =
    List(s("import") ~> skipLWS ~> string.map(Import(_)),
         P.apply2(attempt(ident <~ skipLWS <~ '=' <~ skipLWS), value)(Bind(_, _)),
         P.apply2(attempt(ident <~ skipLWS <~ '{' <~ skipLWS),
                  directives <~ skipLWS <~ '}')(Group(_, _))).concatenate

  lazy val directives: Parser[List[Directive]] =
    (skipLWS ~> directive <~ skipHWS) *
    (satisfy(c => c == '\r' || c == '\n'))

  sealed trait Skip
  case object Space extends Skip
  case object Comment extends Skip

  // Skip lines, comments, or horizontal white space
  lazy val skipLWS: Parser[Unit] = {
    def go(s: Skip, c: Char) = (s, c) match {
      case (Space, c) if (c.isWhitespace) => Some(Space)
      case (Space, '#') => Some(Comment)
      case (Space, _) => None
      case (Comment, '\r') => Some(Space)
      case (Comment, '\n') => Some(Space)
      case (Comment, _) => Some(Comment)
    }
    scan(Space:Skip)(go) ~> ok(())
  }

  // Skip comments or horizontal whitespace
  lazy val skipHWS: Parser[Unit] = {
    def go(s: Skip, c: Char) = (s, c) match {
      case (Space, ' ') => Some(Space)
      case (Space, '\t') => Some(Space)
      case (Space, '#') => Some(Space)
      case (Space, _) => None
      case (Comment, '\r') => None
      case (Comment, '\n') => None
      case (Comment, _) => Some(Comment)
    }
    scan(Space:Skip)(go) ~> ok(())
  }

  lazy val ident: Parser[Name] = for {
    n <- P.apply2(satisfy(c => Character.isAlphabetic(c)), takeWhile(isCont))(_ +: _)
    _ <- when(n == "import") {
      err(s"reserved word ($n) used as an identifier"): Parser[Unit]
    }
  } yield n

  def isCont(c: Char) = Character.isAlphabetic(c) || c == '_' || c == '-'
  def isChar(b: Boolean, c: Char) =
    if (b) Some(false) else if (c == '"') None else Some(c == '\\')

  def s(s: String): Parser[String] = s

  lazy val value: Parser[CfgValue] = List(
    s("on") ~> ok(CfgBool(true)),
    s("off") ~> ok(CfgBool(false)),
    s("true") ~> ok(CfgBool(true)),
    s("false") ~> ok(CfgBool(false)),
    string.map(CfgText(_)),
    scientific.map(s => CfgNumber(s.toDouble)),
    brackets('[', ']', (value <~ skipLWS) * (char(',') <~ skipLWS)).map(CfgList(_))
  ).concatenate

  lazy val string: Parser[String] = for {
    s <- '"' ~> scan(false)(isChar) <~ '"'
    x <- if (s contains "\\") unescape(s) else ok(s)
  } yield x

  def brackets[A](open: Char, close: Char, p: => Parser[A]): Parser[A] =
    open ~> skipLWS ~> p <~ close

  def embed[A](p: Parser[A], s: String): Parser[A] = (p parseOnly s).fold(
    e => err(e),
    v => ok(v))

  def unescape(s: String): Parser[String] = {
    def p(acc: String): Parser[String] = for {
      h <- takeWhile(_ != '\\')
      rest = {
        def cont(c: Char) = p(acc ++ h :+ c)
        for {
          c <- '\\' ~> satisfy(_.toString matches "[ntru\"\\\\]")
          r <- c match {
            case 'n' => cont('\n')
            case 't' => cont('\t')
            case 'r' => cont('\r')
            case '"' => cont('"')
            case '\\' => cont('\\')
            case _ => hexQuad flatMap cont
          }
        } yield r
      }
      done <- atEnd
      r <- if (done) ok(acc ++ h) else rest
    } yield r
    embed(p(""), s)
  }

  lazy val hexadecimal: Parser[Long] = {
    def step(a: Long, c: Char) = (a << 4) + Integer.parseInt(c.toString, 16)
    takeWhile1(_.toString matches "[0-9a-fA-F]").map(_.foldLeft(0L)(step))
  }

  lazy val hexQuad: Parser[Char] = for {
    a <- take(4).flatMap(embed(hexadecimal, _))
    r <- if (a < 0xd800 || a > 0xdfff) ok(a.toChar) else for {
      b <- (s("\\u") ~> take(4)).flatMap(embed(hexadecimal, _))
      r <- if (a <= 0xdbff && b >= 0xdc00 && b <= 0xdfff)
             ok((((a - 0xd800) << 10) + (b - 0xdc00) + 0x10000).toChar)
           else err("invalid UTF-16 surrogates")
    } yield r
  } yield r

  /**
   * Parse a string interpolation spec. The sequence `$$` is treated as a single
   * `$` character. The sequence `$(` begins a section to be interpolated,
   * and `)` ends it.
   */
  def interp: Parser[List[Interpolation]] = {
    def p(acc: List[Interpolation]): Parser[List[Interpolation]] = for {
      h <- takeWhile(_ != '$').map(Literal(_))
      rest = {
        def cont(x: Interpolation): Parser[List[Interpolation]] = p(x :: h :: acc)
        for {
          c <- '$' ~> satisfy(c => c == '$' || c == '(')
          r <- if (c == '$') cont(Literal("$"))
               else (takeWhile1(_ != ')') <~ ')').flatMap(x => cont(Interpolate(x)))
        } yield r
      }
      done <- atEnd
      r <- if (done) ok(h :: acc) else rest
    } yield r
    p(Nil).map(_.reverse)
  }
}
