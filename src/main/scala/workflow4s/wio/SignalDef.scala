package workflow4s.wio

import scala.reflect.ClassTag

case class SignalDef[Req, Resp]()(implicit val reqCt: ClassTag[Req], val respCt: ClassTag[Resp])
