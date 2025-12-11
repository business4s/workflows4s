package workflows4s.wio.builders

import scala.reflect.ClassTag

import workflows4s.wio.*
import workflows4s.wio.WIO.HandleSignal
import workflows4s.wio.internal.{EventHandler, SignalHandler}
import workflows4s.wio.model.ModelUtils

object HandleSignalBuilder {

  trait Step0[Ctx <: WorkflowContext]() {

    def handleSignal[Req, Resp](signalDef: SignalDef[Req, Resp]): Step01[Req, Resp] = Step01[Req, Resp](signalDef)

    class Step01[Req, Resp](signalDef: SignalDef[Req, Resp]) {

      def using[Input] = Step1[Input]()

      class Step1[Input] {

        /** Handle signal with side effects. The F parameter should match the workflow context's effect type. The function should return F[Evt] where
          * F is the effect type defined in the WorkflowContext.
          */
        def withSideEffects[F[_], Evt <: WCEvent[Ctx]](f: (Input, Req) => F[Evt])(using evtCt: ClassTag[Evt]): Step2[Evt] =
          Step2(f.asInstanceOf[(Input, Req) => Any], evtCt)

        /** Handle signal purely (no side effects). */
        def purely[Evt <: WCEvent[Ctx]](f: (Input, Req) => Evt)(using evtCt: ClassTag[Evt]): Step2[Evt] = {
          // Store as pure function, evaluator wraps in effect
          val wrapped: (Input, Req) => Any = (x, y) => f(x, y)
          Step2(wrapped, evtCt)
        }

        class Step2[Evt <: WCEvent[Ctx]](signalHandler: (Input, Req) => Any, evtCt: ClassTag[Evt]) {

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

              def named(operationName: String = null, signalName: String = null): WIO[Input, Err, Out, Ctx] =
                Step4(
                  responseBuilder,
                  Option(operationName).orElse(this.operationName),
                  Option(signalName).orElse(this.signalName),
                ).done

              def autoNamed(using n: sourcecode.Name): WIO[Input, Err, Out, Ctx] = named(operationName = ModelUtils.prettifyName(n.value))

              def done: WIO.IHandleSignal[Input, Err, Out, Ctx] = {
                val combined: (Input, Evt) => (Either[Err, Out], Resp)                   = (s: Input, e: Evt) => (eventHandler(s, e), responseBuilder(s, e))
                val eh: EventHandler[Input, (Either[Err, Out], Resp), WCEvent[Ctx], Evt] = EventHandler(evtCt.unapply, identity, combined)
                val sh: SignalHandler[Req, Evt, Input]                                   = SignalHandler(signalHandler)
                val meta                                                                 = HandleSignal.Meta(errorMeta, signalName.getOrElse(signalDef.name), operationName)
                WIO.HandleSignal(signalDef, sh, eh, meta)
              }

            }

          }

        }

      }
    }
  }

}
