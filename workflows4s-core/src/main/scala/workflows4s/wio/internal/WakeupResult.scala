package workflows4s.wio.internal

import cats.effect.IO

import java.time.Instant

sealed trait WakeupResult[Event, F[_]] {
  def hasEffect: Boolean = this match {
    case _: WakeupResult.Noop[?, ?]      => false
    case _: WakeupResult.Processed[?, ?] => true
  }
}

object WakeupResult {

  type Raw[Event, F[_]] = Option[F[Either[Instant, Event]]]

  case class Noop[Event, F[_]]() extends WakeupResult[Event, F]
  // Helper for easier access to Noop without parentheses
  def noop[E, F[_]]: WakeupResult[E, F] = Noop[E, F]()

  case class Processed[Event, F[_]](result: F[ProcessingResult[Event]]) extends WakeupResult[Event, F]

  extension [Event, F[_]](wr: WakeupResult[Event, F]) {
    def toRaw: Option[F[ProcessingResult[Event]]] = wr match {
      case Noop()           => None
      case Processed(value) => Some(value)
    }
  }

  sealed trait ProcessingResult[+Event]

  object ProcessingResult {
    // We successfully got an event
    case class Proceeded[Evt](event: Evt) extends ProcessingResult[Evt]

    // We didn't get an event, but we should try again at this time (Timer/Retry)
    case class Delayed(at: Instant) extends ProcessingResult[Nothing]

    // The execution crashed with an exception
    case class Failed(error: Throwable) extends ProcessingResult[Nothing]
  }

  def fromRaw[Evt, F[_]](raw: Option[F[Either[Instant, Evt]]]): WakeupResult[Evt, F] = {
    raw match {
      case Some(f) =>
        val processed = f
          .asInstanceOf[IO[Either[Instant, Evt]]]
          .map {
            case Right(evt)  => ProcessingResult.Proceeded(evt)
            case Left(retry) => ProcessingResult.Delayed(retry) // Preserving the retry time!
          }
          .handleErrorWith(err => IO.pure(ProcessingResult.Failed(err)))
          .asInstanceOf[F[ProcessingResult[Evt]]] // Preserving the actual exception!

        WakeupResult.Processed(processed)
      case None    =>
        WakeupResult.Noop()
    }
  }

}
