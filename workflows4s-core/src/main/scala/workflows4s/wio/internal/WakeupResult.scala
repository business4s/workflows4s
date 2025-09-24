package workflows4s.wio.internal

import cats.effect.IO
import cats.implicits.catsSyntaxEitherId

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

  type Raw[Event] = Option[IO[Either[Instant, Event]]]

  case object Noop                                                  extends WakeupResult[Nothing]
  case class Processed[+Event](result: IO[ProcessingResult[Event]]) extends WakeupResult[Event]

  sealed trait ProcessingResult[+Event] {
    def toRaw: Either[Instant, Event] = this match {
      case ProcessingResult.Proceeded(event) => event.asRight
      case ProcessingResult.Failed(retry)    => retry.asLeft
    }
  }
  object ProcessingResult               {
    case class Proceeded[+Event](event: Event) extends ProcessingResult[Event]
    case class Failed(retry: Instant)          extends ProcessingResult[Nothing]
  }

  def fromRaw[Event](raw: Raw[Event]): WakeupResult[Event] = raw match {
    case Some(value) =>
      Processed(value.map({
        case Left(value)  => ProcessingResult.Failed(value)
        case Right(value) => ProcessingResult.Proceeded(value)
      }))
    case None        => Noop
  }

}
