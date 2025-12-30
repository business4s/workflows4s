package workflows4s.example.docs

import cats.effect.IO
import workflows4s.cats.CatsEffect.given
import workflows4s.example.docs.Context.WIO

import java.net.UnknownHostException
import java.time.Duration
import java.util.concurrent.TimeoutException
import scala.annotation.nowarn

@nowarn("msg=unused")
object RetryExample {

  // start_doc_simple
  val doSomething: WIO[Any, Nothing, MyState] = WIO.pure(MyState(1)).autoNamed

  val withRetry = doSomething
    .retryIn {
      case _: TimeoutException     => Duration.ofMinutes(2)
      case _: UnknownHostException => Duration.ofMinutes(15)
    }
  // end_doc_simple

  // start_doc_full
  val doSomethingFull: WIO[Any, Nothing, MyState] = WIO.pure(MyState(1)).autoNamed

  val withRetryFull = doSomethingFull
    .retry { (error, state, now) =>
      error match {
        case _: TimeoutException => IO.pure(Some(now.plus(Duration.ofMinutes(2))))
        case _                   => IO.pure(None) // Don't retry other errors
      }
    }
  // end_doc_full
}
