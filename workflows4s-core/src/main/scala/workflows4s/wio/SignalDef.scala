package workflows4s.wio

import workflows4s.wio.model.ModelUtils

import scala.reflect.ClassTag

class SignalDef[Req, Resp](explicitName: Option[String])(using val reqCt: ClassTag[Req], val respCt: ClassTag[Resp]) {
  def name: String = explicitName.getOrElse(ModelUtils.getPrettyNameForClass(reqCt))
}

object SignalDef {
  def apply[Req: ClassTag, Resp: ClassTag](name: String = null): SignalDef[Req, Resp] =
    new SignalDef(Option(name))
}