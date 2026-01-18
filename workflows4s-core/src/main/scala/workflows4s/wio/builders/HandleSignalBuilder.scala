package workflows4s.wio.builders

import scala.reflect.ClassTag

import workflows4s.wio.*
import workflows4s.wio.WIO.HandleSignal
import workflows4s.wio.internal.{EventHandler, SignalHandler}
import workflows4s.wio.model.ModelUtils
import workflows4s.runtime.instanceengine.Effect

trait HandleSignalBuilderStep0[F[_], Ctx <: WorkflowContext] {

  def handleSignal[Req, Resp](signalDef: SignalDef[Req, Resp]): Step01[Req, Resp] = Step01[Req, Resp](signalDef)

  class Step01[Req, Resp](signalDef: SignalDef[Req, Resp]) {

    def using[Input] = Step1[Input]()

    class Step1[Input] {

      def withSideEffects[Evt <: WCEvent[Ctx]](f: (Input, Req) => F[Evt])(using evtCt: ClassTag[Evt]): Step2[Evt] = Step2(f, evtCt)

      def purely[Evt <: WCEvent[Ctx]](f: (Input, Req) => Evt)(using evtCt: ClassTag[Evt], E: Effect[F]): Step2[Evt] =
        Step2((x, y) => E.pure(f(x, y)), evtCt)

      class Step2[Evt <: WCEvent[Ctx]](signalHandler: (Input, Req) => F[Evt], evtCt: ClassTag[Evt]) {

        def handleEvent[Out <: WCState[Ctx]](f: (Input, Evt) => Out): Step3[Nothing, Out] =
          Step3({ (x, y) => Right(f(x, y)) }, ErrorMeta.noError)

        def handleEventWithError[Err, Out <: WCState[Ctx]](f: (Input, Evt) => Either[Err, Out])(using
            errorMeta: ErrorMeta[Err],
        ): Step3[Err, Out] =
          Step3(f, errorMeta)

        class Step3[Err, Out <: WCState[Ctx]](
            eventHandler: (Input, Evt) => Either[Err, Out],
            errorMeta: ErrorMeta[Err],
        ) {

          def produceResponse(f: (Input, Evt) => Resp): Step4 = Step4(f, None, None)

          def voidResponse(using ev: Unit =:= Resp): Step4 = Step4((_, _) => (), None, None)

          class Step4(responseBuilder: (Input, Evt) => Resp, operationName: Option[String], signalName: Option[String]) {

            def named(operationName: String = null, signalName: String = null): WIO[F, Input, Err, Out, Ctx] =
              Step4(
                responseBuilder,
                Option(operationName).orElse(this.operationName),
                Option(signalName).orElse(this.signalName),
              ).done

            def autoNamed(using n: sourcecode.Name): WIO[F, Input, Err, Out, Ctx] = named(operationName = ModelUtils.prettifyName(n.value))

            def done: WIO.IHandleSignal[F, Input, Err, Out, Ctx] = {
              val combined: (Input, Evt) => (Either[Err, Out], Resp)                   = (s: Input, e: Evt) => (eventHandler(s, e), responseBuilder(s, e))
              val eh: EventHandler[Input, (Either[Err, Out], Resp), WCEvent[Ctx], Evt] = EventHandler(evtCt.unapply, identity, combined)
              val sh: SignalHandler[F, Req, Evt, Input]                                = SignalHandler(signalHandler)
              val meta                                                                 = HandleSignal.Meta(errorMeta, signalName.getOrElse(signalDef.name), operationName)
              WIO.HandleSignal(signalDef, sh, eh, meta)
            }

          }

        }

      }

    }
  }
}

object HandleSignalBuilder {
  type Step0[F[_], Ctx <: WorkflowContext] = HandleSignalBuilderStep0[F, Ctx]
}
