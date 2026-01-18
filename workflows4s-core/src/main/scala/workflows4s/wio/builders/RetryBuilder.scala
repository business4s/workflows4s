package workflows4s.wio.builders

import workflows4s.runtime.instanceengine.Effect
import workflows4s.wio.internal.EventHandler
import workflows4s.wio.{WCEvent, WCState, WIO, WorkflowContext}

import java.time.{Duration, Instant}
import scala.reflect.ClassTag

class RetryBuilderStep0[F[_], In, Err, Out <: WCState[Ctx], Ctx <: WorkflowContext](base: WIO[F, In, Err, Out, Ctx])(using E: Effect[F]) {

  object statelessly {

    type HandlerInput = (stepInput: In, error: Throwable, workflowState: WCState[Ctx])

    def wakeupAt(onError: HandlerInput => F[Option[Instant]]): WIO[F, In, Err, Out, Ctx] = {
      val mode = WIO.Retry.Mode.Stateless[F, Ctx, In]((in, err, state, _) =>
        E.map(onError(in, err, state))({
          case Some(value) => WIO.Retry.Stateless.Result.ScheduleWakeup(value)
          case None        => WIO.Retry.Stateless.Result.Ignore
        }),
      )
      WIO.Retry(base, mode)
    }

    def wakeupIn(onError: PartialFunction[Throwable, Duration]): WIO[F, In, Err, Out, Ctx] = {
      val mode = WIO.Retry.Mode.Stateless[F, Ctx, In]((_, err, _, now) => {
        E.pure(onError.lift(err) match {
          case Some(backoff) => WIO.Retry.Stateless.Result.ScheduleWakeup(now.plus(backoff))
          case None          => WIO.Retry.Stateless.Result.Ignore
        })
      })
      WIO.Retry(base, mode)
    }

  }

  def usingState[RetryState]: StatefulBuilder[RetryState] = new StatefulBuilder[RetryState]

  class StatefulBuilder[RetryState] {

    type OnErrorInput = (stepInput: In, error: Throwable, workflowState: WCState[Ctx], retryState: Option[RetryState])

    def onError[Event <: WCEvent[Ctx]](onError: OnErrorInput => F[WIO.Retry.Stateful.Result[Event]])(using ClassTag[Event]): Step1[Event] =
      new Step1[Event](onError)

    class Step1[Event <: WCEvent[Ctx]](onError: OnErrorInput => F[WIO.Retry.Stateful.Result[Event]])(using evtCt: ClassTag[Event]) {

      type EventHandlerInput = (stepInput: In, event: Event, workflowState: WCState[Ctx], retryState: Option[RetryState])

      def handleEventsWith(onEvent: EventHandlerInput => Either[RetryState, Either[Err, Out]]): WIO.Retry[F, Ctx, In, Err, Out] = {
        val evtHandler =
          EventHandler[WCEvent[Ctx], (In, WCState[Ctx], Option[RetryState]), Either[RetryState, Either[Err, Out]], Event](
            evtCt.unapply,
            identity,
            (in, evt) => onEvent(in._1, evt, in._2, in._3),
          )
        val mode       = WIO.Retry.Mode.Stateful[F, Ctx, Event, In, Err, Out, RetryState](onError, evtHandler, None)
        WIO.Retry(base, mode)
      }
    }
  }
}

object RetryBuilder {
  type Step0[F[_], In, Err, Out <: WCState[Ctx], Ctx <: WorkflowContext] = RetryBuilderStep0[F, In, Err, Out, Ctx]
}
