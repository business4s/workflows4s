package workflow4s.wio

import cats.effect.IO

sealed trait QueryResponse[Resp]

object QueryResponse {

  case class Ok[Resp](value: Resp)   extends QueryResponse[Resp]
  case class UnexpectedQuery[Resp]() extends QueryResponse[Resp]

}
