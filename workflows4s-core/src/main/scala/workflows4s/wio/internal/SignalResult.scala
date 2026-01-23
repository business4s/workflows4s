package workflows4s.wio.internal

import cats.effect.IO

sealed trait SignalResult[+Event, +Resp] {

  def hasEffect: Boolean = this match {
    case SignalResult.UnexpectedSignal => false
    case SignalResult.Processed(_)     => true
    case SignalResult.Redelivered(_)   => false
  }

  def toRaw: Option[IO[(Event, Resp)]] = this match {
    case SignalResult.UnexpectedSignal    => None
    case SignalResult.Processed(resultIO) => Some(resultIO.map(x => (x.event, x.response)))
    case SignalResult.Redelivered(_)      => None
  }

  /** Get the response, whether from fresh processing or redelivery */
  def responseIO: Option[IO[Resp]] = this match {
    case SignalResult.UnexpectedSignal    => None
    case SignalResult.Processed(resultIO) => Some(resultIO.map(_.response))
    case SignalResult.Redelivered(resp)   => Some(IO.pure(resp))
  }
}

object SignalResult {

  type Raw[Event, Resp] = Option[IO[(Event, Resp)]]

  case object UnexpectedSignal                                                     extends SignalResult[Nothing, Nothing]
  case class Processed[+Event, +Resp](resultIO: IO[ProcessingResult[Event, Resp]]) extends SignalResult[Event, Resp]

  /** Signal was redelivered - already processed, response reconstructed from stored event */
  case class Redelivered[+Resp](response: Resp) extends SignalResult[Nothing, Resp]

  case class ProcessingResult[+Event, +Resp](event: Event, response: Resp)

  def fromRaw[Event, Resp](raw: Raw[Event, Resp]): SignalResult[Event, Resp] = raw match {
    case Some(value) => Processed(value.map(ProcessingResult(_, _)))
    case None        => UnexpectedSignal
  }
}
