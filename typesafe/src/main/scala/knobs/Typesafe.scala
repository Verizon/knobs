package knobs
import com.typesafe.config.{Config => TC, _}
import scala.collection.JavaConversions._
import scalaz.concurrent.Task

object Typesafe {
  def config: Task[Config] = Task {
    val cfg = ConfigFactory.load
    def go(o: ConfigObject): Env =
      o.foldLeft(Config.empty.env) {
        case (m, (k, v)) => v.valueType match {
          case ConfigValueType.OBJECT => go(cfg.getObject(k))
          case ConfigValueType.NULL => m
          case _ =>
            def convert(v: ConfigValue): CfgValue = v.valueType match {
              case ConfigValueType.BOOLEAN =>
                CfgBool(cfg.getBoolean(k))
              case ConfigValueType.LIST =>
                CfgList(cfg.getList(k).toList.map(convert))
              case ConfigValueType.NUMBER =>
                CfgNumber(cfg.getDouble(k))
              case ConfigValueType.STRING =>
                CfgText(cfg.getString(k))
              case x => sys.error(s"Can't convert $v to a CfgValue")
            }
            m + (k -> convert(v))
        }
      }
    Config(go(cfg.root))
  }
}

