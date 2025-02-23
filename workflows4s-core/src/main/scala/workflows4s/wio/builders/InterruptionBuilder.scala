package workflows4s.wio.builders

import java.time.Instant
import scala.jdk.DurationConverters.ScalaDurationOps
import scala.reflect.ClassTag
import cats.effect.IO
import workflows4s.wio.*
import workflows4s.wio.WIO.HandleInterruption.InterruptionType
import workflows4s.wio.WIO.{InterruptionSource, Timer}
import workflows4s.wio.internal.{EventHandler, SignalHandler}
import workflows4s.wio.model.ModelUtils

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

          def produceResponse(f: (Input, Evt) => Resp): Step4 = Step4(f, None, None)
          def voidResponse(using ev: Unit =:= Resp): Step4    = Step4((x, y) => (), None, None)

          class Step4(responseBuilder: (Input, Evt) => Resp, operationName: Option[String], signalName: Option[String])
              extends ContinuationBuilder[Err, Out] {

            def named(operationName: String = null, signalName: String = null): Step4 =
              Step4(responseBuilder, Option(operationName).orElse(this.operationName), Option(signalName).orElse(this.signalName))

            def autoNamed()(using n: sourcecode.Name): Step4 = named(operationName = ModelUtils.prettifyName(n.value))

            lazy val source: WIO.InterruptionSource[Input, Err, Out, Ctx] = {
              val combined: (Input, Evt) => (Either[Err, Out], Resp)                   = (s: Input, e: Evt) => (eventHandler(s, e), responseBuilder(s, e))
              val eh: EventHandler[Input, (Either[Err, Out], Resp), WCEvent[Ctx], Evt] = EventHandler(evtCt.unapply, identity, combined)
              val sh: SignalHandler[Req, Evt, Input]                                   = SignalHandler(signalHandler)(using signalDef.reqCt)
              val meta                                                                 = WIO.HandleSignal.Meta(errorMeta, signalName.getOrElse(ModelUtils.getPrettyNameForClass(signalDef.reqCt)), operationName)
              val handleSignal: WIO.HandleSignal[Ctx, Input, Out, Err, Req, Resp, Evt] = WIO.HandleSignal(signalDef, sh, eh, meta)
              handleSignal
            }

            override def tpe: InterruptionType = InterruptionType.Signal
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
            private val name: Option[String] = None,
        ) extends ContinuationBuilder[Nothing, Input] {

          def named(timerName: String): Step3 = this.copy(name = Some(timerName))

          def autoNamed(using name: sourcecode.Name): Step3 = this.copy(name = Some(ModelUtils.prettifyName(name.value)))

          lazy val source: WIO.InterruptionSource[Input, Nothing, WCState[Ctx], Ctx] =
            WIO.Timer(durationSource, startedEventHandler, name, releasedEventHandler)

          override def tpe: InterruptionType = InterruptionType.Timer
        }
      }

    }

    trait ContinuationBuilder[Err, Out <: WCState[Ctx]] {

      def source: InterruptionSource[Input, Err, Out, Ctx]
      def tpe: InterruptionType

      // TODO this could be a method on interruption, doesnt have to prevent builder from completing
      def andThen[FinalErr, FinalOut <: WCState[Ctx]](
          f: WIO[Input, Err, Out, Ctx] => WIO[Input, FinalErr, FinalOut, Ctx],
      ): WIO.Interruption[Ctx, FinalErr, FinalOut] = {
        WIO.Interruption(f(source), tpe)
      }

      def noFollowupSteps: WIO.Interruption[Ctx, Err, Out] = andThen(identity)

    }

  }

}
