package workflows4s.example.docs

import cats.effect.IO
import cats.implicits.catsSyntaxEitherId
import workflows4s.example.docs
import workflows4s.example.docs.Context.WIO

import java.net.UnknownHostException
import java.time.{Duration, Instant}
import java.util.concurrent.TimeoutException
import scala.annotation.nowarn

@nowarn("msg=unused|exhaustive")
object RetryExample {

  // start_doc_simple
  val doSomething: WIO[Any, Nothing, MyState] = WIO.pure(MyState(1)).autoNamed

  val withRetry = doSomething.retry.statelessly.wakeupIn {
    case _: TimeoutException     => Duration.ofMinutes(2)
    case _: UnknownHostException => Duration.ofMinutes(15)
  }
  // end_doc_simple

  {
    // start_doc_full
    val doSomething: WIO[Any, Nothing, MyState] = WIO.pure(MyState(1)).autoNamed

    val withRetry = doSomething.retry.statelessly.wakeupAt { (input, error, state) =>
      error match {
        case _: TimeoutException => IO.pure(Some(Instant.now().plus(Duration.ofMinutes(2))))
        case _                   => IO.pure(None) // Don't retry other errors
      }
    }
    // end_doc_full
  }

  {
    // start_doc_stateful_full
    val doSomething: WIO[Any, Nothing, MyState] = WIO.pure(MyState(1)).autoNamed
    type RetryCounter = Int

    val withRetry: WIO[Any, Nothing, MyState] =
      doSomething.retry
        .usingState[RetryCounter]
        .onError(in => {
          in.error match {
            case _: TimeoutException         =>
              IO(
                WIO.Retry.Stateful.Result.ScheduleWakeup(
                  at = Instant.now().plus(Duration.ofMinutes(30)),
                  event = Some(MyRetryEvent),
                ),
              )
            case _: IllegalArgumentException =>
              IO(WIO.Retry.Stateful.Result.Recover(MyEvent()))
            case _                           =>
              IO(WIO.Retry.Stateful.Result.Ignore)
          }
        })
        .handleEventsWith(in => in.event match {
          case MyRetryEvent => (in.retryState.getOrElse(0) + 1).asLeft
          case e: MyEvent => Right(Right(MyState(1)))
        })
    // end_doc_stateful_full
  }
}
