package workflows4s.wio.internal

sealed trait SignalResult[Event, Resp, F[_]] {

  def hasEffect: Boolean = this match {
    case _: SignalResult.UnexpectedSignal[?, ?, ?] => false
    case _: SignalResult.Processed[?, ?, ?]        => true
  }

  // toRaw requires an Effect instance to map the inner value
  def toRaw(using E: workflows4s.runtime.instanceengine.Effect[F]): Option[F[(Event, Resp)]] = this match {
    case _: SignalResult.UnexpectedSignal[?, ?, ?] => None
    case p: SignalResult.Processed[Event, Resp, F] =>
      Some(E.map(p.resultF)(x => (x.event, x.response)))
  }
}

object SignalResult {

  type Raw[Event, Resp, F[_]] = Option[F[(Event, Resp)]]

  // Changed to a case class to allow for the F type parameter
  case class UnexpectedSignal[Event, Resp, F[_]]() extends SignalResult[Event, Resp, F]

  // Helper to get an instance without explicit type parameters
  def unexpected[Event, Resp, F[_]]: SignalResult[Event, Resp, F] = UnexpectedSignal[Event, Resp, F]()

  case class Processed[Event, Resp, F[_]](resultF: F[ProcessingResult[Event, Resp]]) extends SignalResult[Event, Resp, F]

  case class ProcessingResult[+Event, +Resp](event: Event, response: Resp)

  def fromRaw[Event, Resp, F[_]](raw: Raw[Event, Resp, F])(using E: workflows4s.runtime.instanceengine.Effect[F]): SignalResult[Event, Resp, F] =
    raw match {
      case Some(value) => Processed(E.map(value)((event, resp) => ProcessingResult(event, resp)))
      case None        => UnexpectedSignal()
    }
}
