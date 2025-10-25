package workflows4s.wio.builders

import cats.effect.IO
import workflows4s.wio.{WCState, WIO, WorkflowContext}

import java.time.Duration

object RetryBuilder {

  class Step0[In, Err, Out <: WCState[Ctx], Ctx <: WorkflowContext](wio: WIO[In, Err, Out, Ctx]) {

    object stateless {
//      def apply(onError: (In, Throwable, WCState[Ctx]) => IO[WIO.Retry.Result[WCState[Ctx]]]): WIO[In, Err, Out, Ctx] = {
//        val strategy = WIO.Retry.Mode.Stateless[Ctx, In]((in, err, state, _) => onError(in, err, state))
//        WIO.Retry(wio, strategy)
//      }
//
//      def retryIn(onError: PartialFunction[Throwable, Duration]): WIO[In, Err, Out, Ctx] = {
//        val strategy = WIO.Retry.Mode.Stateless[Ctx, In]((_, err, _, now) => {
//          IO.pure(onError.lift(err) match {
//            case Some(backoff) => WIO.Retry.Result.ScheduleWakeup(now.plus(backoff))
//            case None          => WIO.Retry.Result.Ignore
//          })
//        })
//        WIO.Retry(wio, strategy)
//      }

    }

    object stateful {}

  }

}
