package workflows4s.wio.builders

import cats.effect.IO
import cats.implicits.catsSyntaxOptionId
import workflows4s.wio.*
import workflows4s.wio.WIO.HandleInterruption.InterruptionType
import workflows4s.wio.WIO.Timer
import workflows4s.wio.internal.{EventHandler, SignalHandler}
import workflows4s.wio.model.ModelUtils

import java.time.Instant
import scala.jdk.DurationConverters.ScalaDurationOps
import scala.reflect.ClassTag

object InterruptionBuilder {

  class Step0[Ctx <: WorkflowContext]() {
    type Input = WCState[Ctx]

    def throughSignal[Req, Resp](signalDef: SignalDef[Req, Resp]): SignalInterruptionStep1[Req, Resp] = SignalInterruptionStep1[Req, Resp](signalDef)
    def throughTimeout(duration: scala.concurrent.duration.FiniteDuration): TimeoutInterruptionStep1  = TimeoutInterruptionStep1(
      WIO.Timer.DurationSource.Static(duration.toJava),
    )

    class SignalInterruptionStep1[Req, Resp](signalDef: SignalDef[Req, Resp]) {

      def handleAsync[Evt <: WCEvent[Ctx]](f: (Input, Req) => IO[Evt])(using evtCt: ClassTag[Evt]): Step2[Evt] = Step2(f, evtCt)

      def handleSync[Evt <: WCEvent[Ctx]](f: (Input, Req) => Evt)(using evtCt: ClassTag[Evt]): Step2[Evt] =
        Step2((x, y) => IO.pure(f(x, y)), evtCt)

      class Step2[Evt <: WCEvent[Ctx]](signalHandler: (Input, Req) => IO[Evt], evtCt: ClassTag[Evt]) {

        def handleEvent[Out <: WCState[Ctx]](f: (Input, Evt) => Out): Step3[Nothing, Out] =
          Step3({ (x, y) => Right(f(x, y)) }, ErrorMeta.noError)

        def handleEventWithError[Err, Out <: WCState[Ctx]](f: (Input, Evt) => Either[Err, Out])(using
            errorMeta: ErrorMeta[Err],
        ): Step3[Err, Out] = Step3(f, errorMeta)

        class Step3[Err, Out <: WCState[Ctx]](
            eventHandler: (Input, Evt) => Either[Err, Out],
            errorMeta: ErrorMeta[Err],
        ) {

          def produceResponse(f: (Input, Evt, Req) => Resp): Step4 = Step4(f)
          def voidResponse(using ev: Unit =:= Resp): Step4         = Step4((_, _, _) => ())

          class Step4(responseBuilder: (Input, Evt, Req) => Resp) {

            def named(operationName: String = null, signalName: String = null): WIO.Interruption[Ctx, Err, Out] =
              WIO.Interruption(source(Option(operationName), Option(signalName)), tpe)
            def autoNamed(using name: sourcecode.Name): WIO.Interruption[Ctx, Err, Out]                         =
              named(operationName = ModelUtils.prettifyName(name.value))
            def done: WIO.Interruption[Ctx, Err, Out]                                                           =
              WIO.Interruption(source(None, None), tpe)

            private def source(operationName: Option[String], signalName: Option[String]): WIO[Input, Err, Out, Ctx] = {
              val eh: EventHandler[Input, Either[Err, Out], WCEvent[Ctx], Evt] =
                EventHandler.partial[WCEvent[Ctx], Input, Either[Err, Out], Evt](identity, eventHandler)(using evtCt)
              val sh: SignalHandler[Req, Evt, Input]                           = SignalHandler(signalHandler)
              val meta                                                         = WIO.HandleSignal.Meta(errorMeta, signalName.getOrElse(ModelUtils.getPrettyNameForClass(signalDef.reqCt)), operationName)
              val handleSignal: WIO.HandleSignal[Ctx, Input, Out, Err, Req, Resp, Evt] = WIO.HandleSignal(signalDef, sh, eh, responseBuilder, meta)
              handleSignal
            }

            private def tpe: InterruptionType = InterruptionType.Signal
          }

        }

      }

    }

    class TimeoutInterruptionStep1(durationSource: WIO.Timer.DurationSource[Input]) {

      def persistStartThrough[Evt <: WCEvent[Ctx]](
          incorporate: WIO.Timer.Started => Evt,
      )(extractStartTime: Evt => Instant)(using ct: ClassTag[Evt]): Step2 = {
        val evtHanlder: EventHandler[Input, Unit, WCEvent[Ctx], WIO.Timer.Started] = EventHandler(
          ct.unapply.andThen(_.map(x => WIO.Timer.Started(extractStartTime(x)))),
          incorporate,
          (_, _) => (),
        )
        Step2(evtHanlder)
      }

      case class Step2(private val startedEventHandler: EventHandler[Input, Unit, WCEvent[Ctx], Timer.Started]) {

        def persistReleaseThrough[Evt <: WCEvent[Ctx]](
            incorporate: WIO.Timer.Released => Evt,
        )(extractReleaseTime: Evt => Instant)(using ct: ClassTag[Evt]): Step3 = {
          val evtHanlder: EventHandler[WCState[Ctx], Either[Nothing, WCState[Ctx]], WCEvent[Ctx], Timer.Released] = EventHandler(
            ct.unapply.andThen(_.map(x => Timer.Released(extractReleaseTime(x)))),
            incorporate,
            (in, _) => Right(in),
          )
          Step3(evtHanlder)
        }

        case class Step3(
            private val releasedEventHandler: EventHandler[WCState[Ctx], Either[Nothing, WCState[Ctx]], WCEvent[Ctx], Timer.Released],
        ) {

          def named(timerName: String): WIO.Interruption[Ctx, Nothing, WCState[Ctx]]               = WIO.Interruption(source(timerName.some), tpe)
          def autoNamed(using name: sourcecode.Name): WIO.Interruption[Ctx, Nothing, WCState[Ctx]] = named(ModelUtils.prettifyName(name.value))
          def done: WIO.Interruption[Ctx, Nothing, WCState[Ctx]]                                   = WIO.Interruption(source(None), tpe)

          private def source(name: Option[String]): WIO[Input, Nothing, WCState[Ctx], Ctx] =
            WIO.Timer(durationSource, startedEventHandler, name, releasedEventHandler)
          private def tpe: InterruptionType                                                = InterruptionType.Timer
        }
      }

    }

  }

}
