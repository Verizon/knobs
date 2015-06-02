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
    def parse(s: String) = runParser(p, s) match {
      case Left(e) => \/.left(e.pretty.toString)
      case Right((_, r)) => \/.right(r)
    }
    def |:(s: String) = p scope s
  }

  /** Top-level parser of configuration files */
  lazy val topLevel: Parser[List[Directive]] = "configuration" |: {
    directives << skipLWS << realEOF
  }

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

  lazy val importDirective = "import directive" |: {
    word("import") >> skipLWS >> stringLiteral.map(Import(_))
  }

  lazy val bindDirective = "bind directive" |: {
    attempt(ident.sepBy1('.') << skipLWS << '=' << skipLWS).map2(value) { (x, v) =>
      val xs = x.reverse
      xs.tail.foldLeft(Bind(xs.head, v):Directive)((d, g) => Group(g, List(d)))
    }
  }

  lazy val groupDirective = "group directive" |: { for {
    gs <- attempt(ident.sepBy1('.') << skipLWS << '{' << skipLWS)
    ds <- directives << skipLWS << '}'
    xs = gs.reverse
  } yield xs.tail.foldLeft(Group(xs.head, ds):Directive)((d, g) => Group(g, List(d))) }

  // Skip lines, comments, or horizontal white space
  lazy val skipLWS: Parser[Unit] = (newline | comment | whitespace).skipMany

  // Skip comments or horizontal whitespace
  lazy val skipHWS: Parser[Unit] = (comment | whitespace).skipMany

  lazy val newline: Parser[Unit] =
    "newline" |: satisfy(c => c == '\r' || c == '\n').skip

  lazy val whitespace: Parser[Unit] =
    "whitespace" |: satisfy(c => c.isWhitespace && c != '\r' && c != '\n').skip

  lazy val comment: Parser[Unit] = "comment" |: {
    ch('#').attempt >>
    satisfy(c => c != '\r' && c != '\n').skipMany >>
    (newline | realEOF) >>
    unit(())
  }

  def takeWhile(p: Char => Boolean): Parser[List[Char]] = satisfy(p).many

  import scalaparsers.Document._
  import scalaparsers.Diagnostic._

  lazy val ident: Parser[Name] = for {
    n <- satisfy(c => Character.isLetter(c)).map2(takeWhile(isCont))(_ +: _)
    _ <- failWhen[Parser](n == "import", s"reserved word ($n) used as an identifier")
  } yield n.mkString

  lazy val value: Parser[CfgValue] = "value" |: choice(
    word("on") >> unit(CfgBool(true)),
    word("off") >> unit(CfgBool(false)),
    word("true") >> unit(CfgBool(true)),
    word("false") >> unit(CfgBool(false)),
    string.map(CfgText(_)),
    duration.attempt,
    scientific.map(d => CfgNumber(d.toDouble)),
    list.map(CfgList(_))
  )

  def isChar(b: Boolean, c: Char) =
    if (b) Some(false) else if (c == '"') None else Some(c == '\\')

//  lazy val string: Parser[String] =
//    (ch('\"') >> satisfy(_ != '\"').many.map(_.mkString) << ch('\"')) scope "string literal"

  lazy val stringChar = stringLetter.map(Some(_)) | stringEscape

  // 22.toChar was '\026', but one who knows the intent could improve away the magic number
  private lazy val stringLetter = satisfy(c => (c != '"') && (c != '\\') && (c > 22.toChar))

  private lazy val stringEscape = ch('\\') >> {
    (satisfy(_.isWhitespace).skipSome >> ch('\\')).as(None) |
    escapeCode.map(Some(_))
  }

  private val charEscMagic: Map[Char, Char] = "bfnrt\\\"'".zip("\b\f\n\r\t\\\"'").toMap

  private lazy val escapeCode = "escape code" |:
    choice(charEscMagic.toSeq.map { case (c,d) => ch(c) as d } :_*)

  lazy val string: Parser[String] = "string literal" |:
    stringChar.many.between('"','"').map(_.sequence[Option,Char].getOrElse(List()).mkString)

  lazy val list: Parser[List[CfgValue]] = "list" |:
    (ch('[') >> (skipLWS >> value << skipLWS).sepBy(ch(',') << skipLWS) << ']')

  def isCont(c: Char) = Character.isLetterOrDigit(c) || c == '_' || c == '-'

  lazy val signedInt: Parser[BigInt] =
    (ch('-') >> decimal).map(- _) | (ch('+') >> decimal) | decimal

  lazy val scientific: Parser[BigDecimal] = "numeric literal" |: { for {
    positive <- satisfy(c => c == '-' || c == '+').map(_ == '+') | unit(true)
    n <- decimal
    s <- (satisfy(_ == '.') >> takeWhile(_.isDigit).map(f =>
      BigDecimal(n + "." + f.mkString))) | unit(BigDecimal(n))
    sCoeff = if (positive) s else (- s)
    r <- satisfy(c => c == 'e' || c == 'E') >>
         signedInt.flatMap(x =>
           if (x > Int.MaxValue) fail[Parser](s"Exponent too large: $x")
           else unit(s * BigDecimal(10).pow(x.toInt))) | unit(sCoeff)
  } yield r }

  private def addDigit(a: BigInt, c: Char) = a * 10 + (c - 48)

  lazy val digit = "digit" |: satisfy(_.isDigit)

  lazy val decimal: Parser[BigInt] =
    digit.some.map(_.foldLeft(BigInt(0))(addDigit))

  lazy val duration: Parser[CfgValue] = "duration" |: { for {
    d <- (scientific << whitespace.skipOptional)
    x <- "time unit" |: takeWhile(_.isLetter).map(_.mkString)
    r <- \/.fromTryCatchNonFatal(Duration.create(1, x)).fold(
      e => fail(e.getMessage),
      o => unit(CfgDuration(o * d.toDouble)))
  } yield r }

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
