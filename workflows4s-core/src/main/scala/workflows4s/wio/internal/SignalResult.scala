package workflows4s.wio.internal

import cats.effect.IO

sealed trait SignalResult[Event, Resp, F[_]] {

  def hasEffect: Boolean = this match {
    case _: SignalResult.UnexpectedSignal[?, ?, ?] => false
    case _: SignalResult.Processed[?, ?, ?]        => true
  }

  // toRaw requires an Effect instance to map the inner value
  def toRaw: Option[F[(Event, Resp)]] = this match {
    case _: SignalResult.UnexpectedSignal[?, ?, ?] => None
    case p: SignalResult.Processed[Event, Resp, F] =>
      Some(p.resultF.asInstanceOf[IO[SignalResult.ProcessingResult[Event, Resp]]].map(x => (x.event, x.response)).asInstanceOf[F[(Event, Resp)]])
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

  def fromRaw[Event, Resp, F[_]](raw: Raw[Event, Resp, F]): SignalResult[Event, Resp, F] =
    raw match {
      case Some(value) =>
        Processed(
          value.asInstanceOf[IO[(Event, Resp)]].map((event, resp) => ProcessingResult(event, resp)).asInstanceOf[F[ProcessingResult[Event, Resp]]],
        )
      case None        => UnexpectedSignal()
    }
}
