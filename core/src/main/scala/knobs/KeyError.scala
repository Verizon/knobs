package knobs

case class KeyError(name: String) extends Exception(s"No such key: $name")

