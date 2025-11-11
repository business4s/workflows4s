package workflows4s.wio.internal

import cats.data.Ior
import cats.effect.IO
import cats.syntax.all.*

import java.time.Instant

sealed trait WakeupResult[+Event] {
  def toRaw: WakeupResult.Raw[Event] = this match {
    case WakeupResult.Noop              => None
    case WakeupResult.Processed(result) => Some(result.map(_.toRaw))
  }

  def hasEffect: Boolean = this match {
    case WakeupResult.Noop         => false
    case WakeupResult.Processed(_) => true
  }
}

object WakeupResult {

  type Raw[Event] = Option[IO[Ior[Instant, Event]]]

  case object Noop                                                  extends WakeupResult[Nothing]
  case class Processed[+Event](result: IO[ProcessingResult[Event]]) extends WakeupResult[Event]

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

  def fromRaw[Event](raw: Raw[Event]): WakeupResult[Event] = raw match {
    case Some(value) =>
      Processed(value.map({
        case Ior.Left(a) => ProcessingResult.Failed(a, None)
        case Ior.Right(b) => ProcessingResult.Proceeded(b)
        case Ior.Both(a, b) => ProcessingResult.Failed(a, Some(b))
      }))
    case None        => Noop
  }

}
