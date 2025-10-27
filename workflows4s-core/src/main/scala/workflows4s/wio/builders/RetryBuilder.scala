package workflows4s.wio.builders

import cats.effect.IO
import cats.implicits.catsSyntaxEitherId
import workflows4s.wio.internal.EventHandler
import workflows4s.wio.{WCEvent, WCState, WIO, WorkflowContext}

import java.time.{Duration, Instant}
import scala.annotation.unused
import scala.reflect.ClassTag
import scala.util.NotGiven

object RetryBuilder {

  class Step0[In, Err, Out <: WCState[Ctx], Ctx <: WorkflowContext](base: WIO[In, Err, Out, Ctx]) {

    object statelessly {

      def wakeupAt(onError: (In, Throwable, WCState[Ctx]) => IO[Option[Instant]]): WIO[In, Err, Out, Ctx] = {
        val strategy = WIO.Retry.Mode.Stateless[Ctx, In]((in, err, state, _) =>
          onError(in, err, state).map({
            case Some(value) => WIO.Retry.StatelessResult.ScheduleWakeup(value)
            case None        => WIO.Retry.StatelessResult.Ignore
          }),
        )
        WIO.Retry(base, strategy)
      }

      def wakeupIn(onError: PartialFunction[Throwable, Duration]): WIO[In, Err, Out, Ctx] = {
        val strategy = WIO.Retry.Mode.Stateless[Ctx, In]((_, err, _, now) => {
          IO.pure(onError.lift(err) match {
            case Some(backoff) => WIO.Retry.StatelessResult.ScheduleWakeup(now.plus(backoff))
            case None          => WIO.Retry.StatelessResult.Ignore
          })
        })
        WIO.Retry(base, strategy)
      }

    }

    def usingState[T]: StatefulBuilder[T] = new StatefulBuilder[T]

    class StatefulBuilder[RetryState] {

      def onError[RetryEvent, RecoverEvent](
          onError: (In, Throwable, WCState[Ctx], Option[RetryState]) => IO[WIO.Retry.StatefulResult[RetryEvent, RecoverEvent]],
      )(using retBound: RetryEvent <:< WCEvent[Ctx], recBound: RecoverEvent <:< WCEvent[Ctx]): Step1[RetryEvent & WCEvent[Ctx], RecoverEvent & WCEvent[Ctx]] = {
        new Step1[RetryEvent & WCEvent[Ctx], RecoverEvent & WCEvent[Ctx]](onError.asInstanceOf)
      }

      class Step1[RetryEvent <: WCEvent[Ctx], RecoverEvent <: WCEvent[Ctx]](
          onError: (In, Throwable, WCState[Ctx], Option[RetryState]) => IO[WIO.Retry.StatefulResult[RetryEvent, RecoverEvent]],
      ) {

        /** This variant can be used only if the RetryEvent and RecoverEvent types are different. */
        def handleEventsWith(
            onRetry: (In, RetryEvent, Option[RetryState]) => RetryState,
            onRecover: (In, RecoverEvent, WCState[Ctx]) => Either[Err, Out],
        )(using
            retryEvtCt: ClassTag[RetryEvent],
            recoverEvtCt: ClassTag[RecoverEvent],
            @unused n1: NotGiven[RetryEvent <:< RecoverEvent],
            @unused n2: NotGiven[RecoverEvent <:< RetryEvent],
            retBound: RetryEvent <:< WCEvent[Ctx],
            recBound: RecoverEvent <:< WCEvent[Ctx],
        ) = {
          val evtHandler =
            EventHandler[WCEvent[Ctx], (In, WCState[Ctx], Option[RetryState]), Either[RetryState, Either[Err, Out]], RetryEvent | RecoverEvent](
              x => retryEvtCt.unapply(x).orElse(recoverEvtCt.unapply(x)),
              {
                case x: RetryEvent   => retBound(x)
                case x: RecoverEvent => recBound(x)
              },
              (in, evt) =>
                evt match {
                  case retryEvtCt(value)   => onRetry(in._1, value, in._3).asLeft
                  case recoverEvtCt(value) => onRecover(in._1, value, in._2).asRight
                },
            )
          val mode       = WIO.Retry.Mode.Stateful(onError, evtHandler, None)
          WIO.Retry(base, mode)
        }

        def handleEventsTogetherWith(
            onEvent: (In, RetryEvent | RecoverEvent, WCState[Ctx], Option[RetryState]) => Either[RetryState, Either[Err, Out]],
        )(using ct: ClassTag[RetryEvent | RecoverEvent], sumBound: (RetryEvent | RecoverEvent) <:< WCEvent[Ctx]) = {
          val evtHandler =
            EventHandler[WCEvent[Ctx], (In, WCState[Ctx], Option[RetryState]), Either[RetryState, Either[Err, Out]], RetryEvent | RecoverEvent](
              x => ct.unapply(x),
              x => sumBound(x),
              (in, evt) => onEvent(in._1, evt, in._2, in._3),
            )
          val mode       = WIO.Retry.Mode.Stateful(onError, evtHandler, None)
          WIO.Retry(base, mode)
        }

      }

    }

  }

}
