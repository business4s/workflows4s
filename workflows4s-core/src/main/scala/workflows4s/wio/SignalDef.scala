package workflows4s.wio

import scala.reflect.ClassTag

case class SignalDef[Req, Resp]()(using val reqCt: ClassTag[Req], val respCt: ClassTag[Resp])
