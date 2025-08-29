package workflows4s.wio.internal

import cats.effect.IO

sealed trait SignalResult[+Event, +Resp] {

  def hasEffect: Boolean = this match {
    case SignalResult.UnexpectedSignal => false
    case SignalResult.Processed(_)     => true
  }

  def toRaw: Option[IO[(Event, Resp)]] = this match {
    case SignalResult.UnexpectedSignal    => None
    case SignalResult.Processed(resultIO) => Some(resultIO.map(x => (x.event, x.response)))
  }
}

object SignalResult {

  type Raw[Event, Resp] = Option[IO[(Event, Resp)]]

  case object UnexpectedSignal                                                     extends SignalResult[Nothing, Nothing]
  case class Processed[+Event, +Resp](resultIO: IO[ProcessingResult[Event, Resp]]) extends SignalResult[Event, Resp]

  case class ProcessingResult[+Event, +Resp](event: Event, response: Resp)

  def fromRaw[Event, Resp](raw: Raw[Event, Resp]): SignalResult[Event, Resp] = raw match {
    case Some(value) => Processed(value.map(ProcessingResult(_, _)))
    case None        => UnexpectedSignal
  }
}
