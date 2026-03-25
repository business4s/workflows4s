package workflows4s.wio

import workflows4s.wio.model.ModelUtils

import java.util.UUID
import scala.reflect.ClassTag

/** Identifies an external signal that a workflow can receive.
  *
  * Instances should be defined as stable values shared between the workflow definition and the code that delivers signals at runtime. The `id` must
  * be stable across deployments as it is persisted in the event journal.
  */
case class SignalDef[Req, Resp](id: String, explicitName: Option[String])(using val reqCt: ClassTag[Req], val respCt: ClassTag[Resp]) {
  def name: String = explicitName.getOrElse(ModelUtils.getPrettyNameForClass(reqCt))
}

object SignalDef {
  def apply[Req: ClassTag, Resp: ClassTag](id: String = null, name: String = null): SignalDef[Req, Resp] = {
    new SignalDef(Option(id).getOrElse(UUID.randomUUID().toString), Option(name))
  }
}
