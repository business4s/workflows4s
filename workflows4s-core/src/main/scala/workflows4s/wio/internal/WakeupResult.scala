package workflows4s.wio.internal

import workflows4s.effect.Effect

import java.time.Instant

sealed trait WakeupResult[F[_], Event] {
  def toRaw(using E: Effect[F]): WakeupResult.Raw[F, Event]
  def hasEffect: Boolean
}

object WakeupResult {

  type Raw[F[_], Event] = Option[F[Either[Instant, Event]]]

  case class Noop[F[_]]() extends WakeupResult[F, Nothing] {
    override def toRaw(using E: Effect[F]): Raw[F, Nothing] = None
    override def hasEffect: Boolean                         = false
  }

  case class Processed[F[_], Event](result: F[ProcessingResult[Event]]) extends WakeupResult[F, Event] {
    override def toRaw(using E: Effect[F]): Raw[F, Event] = Some(E.map(result)(_.toRaw))
    override def hasEffect: Boolean                       = true
  }

  sealed trait ProcessingResult[Event] {
    def toRaw: Either[Instant, Event]
  }
  object ProcessingResult              {
    case class Proceeded[Event](event: Event) extends ProcessingResult[Event]   {
      override def toRaw: Either[Instant, Event] = Right(event)
    }
    case class Failed(retry: Instant)         extends ProcessingResult[Nothing] {
      override def toRaw: Either[Instant, Nothing] = Left(retry)
    }
  }

  def fromRaw[F[_]: Effect, Event](raw: Raw[F, Event]): WakeupResult[F, Event] = raw match {
    case Some(value) =>
      Processed[F, Event](Effect[F].map(value) {
        case Left(value)  => ProcessingResult.Failed(value).asInstanceOf[ProcessingResult[Event]]
        case Right(value) => ProcessingResult.Proceeded[Event](value)
      })
    case None        => Noop[F]().asInstanceOf[WakeupResult[F, Event]]
  }

}
