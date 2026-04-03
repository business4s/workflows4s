package workflows4s.wio.internal

import cats.data.Ior
import cats.syntax.all.*

import java.time.Instant

sealed trait WakeupResult[F[_], +Event] {
  def hasEffect: Boolean = this match {
    case WakeupResult.Noop()       => false
    case WakeupResult.Processed(_) => true
  }
}

object WakeupResult {

  type Raw[F[_], Event] = Option[F[Ior[Instant, Event]]]

  case class Noop[F[_]]()                                               extends WakeupResult[F, Nothing]
  case class Processed[F[_], Event](result: F[ProcessingResult[Event]]) extends WakeupResult[F, Event]

  sealed trait ProcessingResult[+Event] {
    def toRaw: Ior[Instant, Event] = this match {
      case ProcessingResult.Proceeded(event)     => event.rightIor
      case ProcessingResult.Failed(retry, event) =>
        event match {
          case Some(value) => Ior.both(retry, value)
          case None        => Ior.left(retry)
        }
    }
  }
  object ProcessingResult               {
    case class Proceeded[+Event](event: Event)                      extends ProcessingResult[Event]
    case class Failed[+Event](retry: Instant, event: Option[Event]) extends ProcessingResult[Event]
  }

  def fromRaw[F[_]: cats.Functor, Event](raw: Raw[F, Event]): WakeupResult[F, Event] = raw match {
    case Some(value) =>
      Processed(value.map({
        case Ior.Left(a)    => ProcessingResult.Failed(a, None)
        case Ior.Right(b)   => ProcessingResult.Proceeded(b)
        case Ior.Both(a, b) => ProcessingResult.Failed(a, Some(b))
      }))
    case None        => Noop()
  }

  def toRaw[F[_]: cats.Functor, Event](result: WakeupResult[F, Event]): Raw[F, Event] = result match {
    case Noop()            => None
    case Processed(result) => Some(cats.Functor[F].map(result)(_.toRaw))
  }

}
