package workflows4s.wio.builders

import cats.effect.IO
import workflows4s.wio.internal.EventHandler
import workflows4s.wio.{WCEvent, WCState, WIO, WorkflowContext}

import java.time.{Duration, Instant}
import scala.reflect.ClassTag

object RetryBuilder {

  class Step0[In, Err, Out <: WCState[Ctx], Ctx <: WorkflowContext](base: WIO[In, Err, Out, Ctx]) {

    object statelessly {

      type HandlerInput = (stepInput: In, error: Throwable, workflowState: WCState[Ctx])

      def wakeupAt(onError: HandlerInput => IO[Option[Instant]]): WIO[In, Err, Out, Ctx] = {
        val mode = WIO.Retry.Mode.Stateless[Ctx, In]((in, err, state, _) =>
          onError(in, err, state).map({
            case Some(value) => WIO.Retry.Stateless.Result.ScheduleWakeup(value)
            case None        => WIO.Retry.Stateless.Result.Ignore
          }),
        )
        WIO.Retry(base, mode)
      }

      def wakeupIn(onError: PartialFunction[Throwable, Duration]): WIO[In, Err, Out, Ctx] = {
        val mode = WIO.Retry.Mode.Stateless[Ctx, In]((_, err, _, now) => {
          IO.pure(onError.lift(err) match {
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

      def onError[Event <: WCEvent[Ctx]](onError: OnErrorInput => IO[WIO.Retry.Stateful.Result[Event]])(using ClassTag[Event]): Step1[Event] =
        new Step1[Event](onError)

      class Step1[Event <: WCEvent[Ctx]](onError: OnErrorInput => IO[WIO.Retry.Stateful.Result[Event]])(using evtCt: ClassTag[Event]) {

        type EventHandlerInput = (stepInput: In, event: Event, workflowState: WCState[Ctx], retryState: Option[RetryState])

        def handleEventsWith(onEvent: EventHandlerInput => Either[RetryState, Either[Err, Out]]): WIO.Retry[Ctx, In, Err, Out] = {
          val evtHandler =
            EventHandler.partial[WCEvent[Ctx], (In, WCState[Ctx], Option[RetryState]), Either[RetryState, Either[Err, Out]], Event](
              identity,
              (in, evt) => onEvent(in._1, evt, in._2, in._3),
            )
          val mode       = WIO.Retry.Mode.Stateful[Ctx, Event, In, Err, Out, RetryState](onError, evtHandler, None)
          WIO.Retry(base, mode)
        }

      }

    }

  }

}
