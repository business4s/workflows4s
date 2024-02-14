package workflow4s.wio

import scala.reflect.ClassTag

case class SignalDef[Req, Resp]()(implicit val respCt: ClassTag[Resp])
