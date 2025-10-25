package workflows4s.wio.builders

import cats.effect.IO
import workflows4s.wio.{WCState, WIO, WorkflowContext}

import java.time.{Duration, Instant}
import scala.annotation.unused

object RetryBuilder {

  class Step0[In, Err, Out <: WCState[Ctx], Ctx <: WorkflowContext](@unused wio: WIO[In, Err, Out, Ctx]) {

    object statelessly {

      def wakeupAt(onError: (In, Throwable, WCState[Ctx]) => IO[Option[Instant]]): WIO[In, Err, Out, Ctx] = {
        val strategy = WIO.Retry.Mode.Stateless[Ctx, In]((in, err, state, _) =>
          onError(in, err, state).map({
            case Some(value) => WIO.Retry.StatelessResult.ScheduleWakeup(value)
            case None        => WIO.Retry.StatelessResult.Ignore
          }),
        )
        WIO.Retry(wio, strategy)
      }

      def wakeupIn(onError: PartialFunction[Throwable, Duration]): WIO[In, Err, Out, Ctx] = {
        val strategy = WIO.Retry.Mode.Stateless[Ctx, In]((_, err, _, now) => {
          IO.pure(onError.lift(err) match {
            case Some(backoff) => WIO.Retry.StatelessResult.ScheduleWakeup(now.plus(backoff))
            case None          => WIO.Retry.StatelessResult.Ignore
          })
        })
        WIO.Retry(wio, strategy)
      }

    }

    object statefully {}

  }

}
