package knobs

/**
 * A small exception modeling class used by `Config.parse` if the parsing failed.
 */
case class ParseException(msg: String) extends RuntimeException {
  override def getMessage: String = msg
}
