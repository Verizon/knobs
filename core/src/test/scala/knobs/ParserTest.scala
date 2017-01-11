package knobs

import org.scalatest._
import scalaz.\/-

import ConfigParser.ParserOps

class ParserTest extends FlatSpec with Matchers with Inside {

  "Parser" should "correctly parse config input with only comments" in {
    val input =
      """#param1=true
        |#param2="hello"""".stripMargin
    ConfigParser.topLevel.parse(input) should be (\/-(Nil))
  }

  it should "parse a bind preceded by a comment" in {
    val input = """
      #Whether to trigger a timeout or not
      test.timeout=true
    """
    val result = ConfigParser.topLevel.parse(input)
    result should be (\/-(List(Group("test", List(Bind("timeout", CfgBool(true)))))))
  }

  it should "parse a group directive" in {
    val input =
      """ac {
        |  # fnord
        |  x=1
        |
        |  y=true
        |
        |  #blorg
        |}
      """.stripMargin
    val expected = \/-(Group("ac", List(
      Bind("x", CfgNumber(1)),
      Bind("y", CfgBool(true)))))
    ConfigParser.groupDirective(withImport = true).parse(input) should be (expected)
  }
}
