package workflows4s.wio.internal

sealed trait SignalResult[F[_], +Event, +Resp] {
  def hasEffect: Boolean = this match {
    case _: SignalResult.UnexpectedSignal[?] => false
    case _: SignalResult.Processed[?, ?, ?]  => true
    case _: SignalResult.Redelivered[?, ?]   => false
  }
}

object SignalResult {

  case class UnexpectedSignal[F[_]]()                                                 extends SignalResult[F, Nothing, Nothing]
  case class Processed[F[_], Event, Resp](resultIO: F[ProcessingResult[Event, Resp]]) extends SignalResult[F, Event, Resp]

  /** Signal was redelivered - already processed, response reconstructed from stored event */
  case class Redelivered[F[_], +Resp](response: Resp) extends SignalResult[F, Nothing, Resp]

  case class ProcessingResult[+Event, +Resp](event: Event, response: Resp)
}
