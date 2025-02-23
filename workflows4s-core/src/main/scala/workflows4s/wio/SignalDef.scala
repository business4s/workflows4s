package workflows4s.wio

import workflows4s.wio.model.ModelUtils

import java.util.UUID
import scala.reflect.ClassTag

case class SignalDef[Req, Resp](id: String, explicitName: Option[String])(using val reqCt: ClassTag[Req], val respCt: ClassTag[Resp]) {
  def name: String = explicitName.getOrElse(ModelUtils.getPrettyNameForClass(reqCt))
}

object SignalDef {
  def apply[Req: ClassTag, Resp: ClassTag](name: String = null, id: String = null): SignalDef[Req, Resp] = {
    // TODO should we make it RT and wrap uuid generation?
    new SignalDef(Option(id).getOrElse(UUID.randomUUID().toString), Option(name))
  }
}
