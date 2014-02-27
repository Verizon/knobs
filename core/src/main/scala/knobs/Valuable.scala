package knobs

sealed trait Valuable[V] {
  def convert(v: V): CfgValue
}

object Valuable {
  def apply[V:Valuable]: Valuable[V] = implicitly[Valuable[V]]

  implicit val stringValue: Valuable[String] = new Valuable[String] {
    def convert(v: String) = {
      import ConfigParser._
      value.parse(v).fold(e => CfgText(v), r => r)
    }
  }
}
