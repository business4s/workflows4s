package workflows4s.wio.internal

import workflows4s.effect.Effect

sealed trait SignalResult[F[_], Event, Resp] {
  def hasEffect: Boolean
  def toRaw(using E: Effect[F]): Option[F[(Event, Resp)]]
}

object SignalResult {

  type Raw[F[_], Event, Resp] = Option[F[(Event, Resp)]]

  case class UnexpectedSignal[F[_]]() extends SignalResult[F, Nothing, Nothing] {
    override def hasEffect: Boolean                                       = false
    override def toRaw(using E: Effect[F]): Option[F[(Nothing, Nothing)]] = None
  }

  case class Processed[F[_], Event, Resp](resultIO: F[ProcessingResult[Event, Resp]]) extends SignalResult[F, Event, Resp] {
    override def hasEffect: Boolean                                  = true
    override def toRaw(using E: Effect[F]): Option[F[(Event, Resp)]] =
      Some(E.map(resultIO)(x => (x.event, x.response)))
  }

  case class ProcessingResult[Event, Resp](event: Event, response: Resp)

  def fromRaw[F[_]: Effect, Event, Resp](raw: Raw[F, Event, Resp]): SignalResult[F, Event, Resp] = raw match {
    case Some(value) => Processed[F, Event, Resp](Effect[F].map(value)(ProcessingResult[Event, Resp](_, _)))
    case None        => UnexpectedSignal[F]().asInstanceOf[SignalResult[F, Event, Resp]]
  }
}
