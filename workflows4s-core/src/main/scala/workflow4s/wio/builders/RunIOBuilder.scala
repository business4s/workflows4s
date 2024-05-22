package workflow4s.wio.builders

import cats.effect.IO
import cats.implicits.catsSyntaxEitherId
import workflow4s.wio.internal.EventHandler
import workflow4s.wio.model.ModelUtils
import workflow4s.wio.{ErrorMeta, WCEvent, WCState, WIO, WorkflowContext}

import scala.reflect.ClassTag

object RunIOBuilder {

  trait Step0[Ctx <: WorkflowContext] {

    def runIO[Input] = new RunIOBuilderStep1[Input]

    class RunIOBuilderStep1[Input] {
      def apply[Evt <: WCEvent[Ctx]: ClassTag](f: Input => IO[Evt]): Step2[Input, Evt] = {
        new Step2[Input, Evt](f)
      }

      class Step2[In, Evt <: WCEvent[Ctx]: ClassTag](getIO: In => IO[Evt]) {
        def handleEvent[Out <: WCState[Ctx]](f: (In, Evt) => Out): Step3[Out, Nothing] =
          Step3((s, e: Evt) => f(s, e).asRight, ErrorMeta.noError)

        def handleEventWithError[Err, Out <: WCState[Ctx]](
            f: (In, Evt) => Either[Err, Out],
        )(implicit errorCt: ErrorMeta[Err]): Step3[Out, Err] = Step3(f, errorCt)

        class Step3[Out <: WCState[Ctx], Err](evtHandler: (In, Evt) => Either[Err, Out], errorMeta: ErrorMeta[_]) {

          def named(name: String): WIO[In, Err, Out, Ctx]                 = build(Some(name))
          def autoNamed(using n: sourcecode.Name): WIO[In, Err, Out, Ctx] = build(Some(ModelUtils.prettifyName(n.value)))
          def done: WIO[In, Err, Out, Ctx]                                = build(None)

          private def build(name: Option[String]): WIO[In, Err, Out, Ctx] =
            WIO.RunIO[Ctx, In, Err, Out, Evt](
              getIO,
              EventHandler(summon[ClassTag[Evt]].unapply, identity, evtHandler),
              WIO.RunIO.Meta(errorMeta, name),
            )
        }

      }

    }
  }
}
