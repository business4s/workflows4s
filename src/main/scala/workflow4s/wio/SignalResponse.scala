package workflow4s.wio

import cats.effect.IO

sealed trait SignalResponse[St, Resp]

object SignalResponse {

  case class Ok[St, Resp](value: IO[(ActiveWorkflow[St, Any], Resp)]) extends SignalResponse[St, Resp]
  case class UnexpectedSignal[St, Resp]()                        extends SignalResponse[St, Resp]

}
