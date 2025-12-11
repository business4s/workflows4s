package workflows4s.wio.builders

import scala.reflect.ClassTag

import cats.implicits.catsSyntaxEitherId
import workflows4s.wio.internal.EventHandler
import workflows4s.wio.model.ModelUtils
import workflows4s.wio.{ErrorMeta, HasEffect, WCEvent, WCState, WIO, WorkflowContext}

object RunIOBuilder {

  trait Step0[Ctx <: WorkflowContext] {

    def runIO[Input] = new RunIOBuilderStep1[Input]

    class RunIOBuilderStep1[Input] {

      /** Build a RunIO step with effect type from WorkflowContext.
        *
        * Uses transparent inline + HasEffect to ensure type safety: the function must return `F[Evt]` where F is the context's effect type. The
        * effect function is stored type-erased and cast back at runtime by the evaluator.
        */
      transparent inline def apply[Evt <: WCEvent[Ctx]: ClassTag](using
          he: HasEffect[Ctx],
      )(f: Input => he.F[Evt]): Step2[Input, Evt] = {
        // Cast to Any for type-erased storage
        new Step2[Input, Evt](f.asInstanceOf[Input => Any])
      }

      class Step2[In, Evt <: WCEvent[Ctx]: ClassTag](getEffect: In => Any) {
        def handleEvent[Out <: WCState[Ctx]](f: (In, Evt) => Out): Step3[Out, Nothing] =
          Step3((s, e: Evt) => f(s, e).asRight, ErrorMeta.noError)

        def handleEventWithError[Err, Out <: WCState[Ctx]](
            f: (In, Evt) => Either[Err, Out],
        )(using errorCt: ErrorMeta[Err]): Step3[Out, Err] = Step3(f, errorCt)

        class Step3[Out <: WCState[Ctx], Err](evtHandler: (In, Evt) => Either[Err, Out], errorMeta: ErrorMeta[?]) {

          def named(name: String, description: String = null): WIO[In, Err, Out, Ctx]                 = build(Some(name), Option(description))
          def autoNamed(description: String = null)(using n: sourcecode.Name): WIO[In, Err, Out, Ctx] =
            build(Some(ModelUtils.prettifyName(n.value)), Option(description))
          def done: WIO[In, Err, Out, Ctx]                                                            = build(None, None)

          private def build(name: Option[String], description: Option[String]): WIO[In, Err, Out, Ctx] =
            WIO.RunIO[Ctx, In, Err, Out, Evt](
              getEffect,
              EventHandler(summon[ClassTag[Evt]].unapply, identity, evtHandler),
              WIO.RunIO.Meta(errorMeta, name, description),
            )
        }

      }

    }
  }
}
