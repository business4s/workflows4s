package workflows4s.wio.builders

import scala.reflect.ClassTag

import cats.implicits.catsSyntaxEitherId
import workflows4s.wio.internal.EventHandler
import workflows4s.wio.model.ModelUtils
import workflows4s.wio.{ErrorMeta, WCEvent, WCState, WIO, WorkflowContext}
import workflows4s.runtime.instanceengine.Effect

trait RunIOBuilderStep0[F[_], Ctx <: WorkflowContext] {

  def runIO[Input] = new RunIOBuilderStep1[Input]

  class RunIOBuilderStep1[Input] {
    def apply[Evt <: WCEvent[Ctx]: ClassTag](f: Input => F[Evt]): Step2[Input, Evt] = {
      new Step2[Input, Evt](f)
    }

    def pure[Evt <: WCEvent[Ctx]: ClassTag](f: Input => Evt)(using E: Effect[F]): Step2[Input, Evt] = {
      new Step2[Input, Evt](in => E.pure(f(in)))
    }

    class Step2[In, Evt <: WCEvent[Ctx]: ClassTag](getIO: In => F[Evt]) {
      def handleEvent[Out <: WCState[Ctx]](f: (In, Evt) => Out): Step3[Out, Nothing] =
        Step3((s, e: Evt) => f(s, e).asRight, ErrorMeta.noError)

      def handleEventWithError[Err, Out <: WCState[Ctx]](
          f: (In, Evt) => Either[Err, Out],
      )(using errorCt: ErrorMeta[Err]): Step3[Out, Err] = Step3(f, errorCt)

      class Step3[Out <: WCState[Ctx], Err](evtHandler: (In, Evt) => Either[Err, Out], errorMeta: ErrorMeta[?]) {

        def named(name: String, description: String = null): WIO[F, In, Err, Out, Ctx]                 = build(Some(name), Option(description))
        def autoNamed(description: String = null)(using n: sourcecode.Name): WIO[F, In, Err, Out, Ctx] =
          build(Some(ModelUtils.prettifyName(n.value)), Option(description))
        def done: WIO[F, In, Err, Out, Ctx]                                                            = build(None, None)

        private def build(name: Option[String], description: Option[String]): WIO[F, In, Err, Out, Ctx] =
          WIO.RunIO[F, Ctx, In, Err, Out, Evt](
            getIO,
            EventHandler(summon[ClassTag[Evt]].unapply, identity, evtHandler),
            WIO.RunIO.Meta(errorMeta, name, description),
          )
      }

    }

  }
}

object RunIOBuilder {
  type Step0[F[_], Ctx <: WorkflowContext] = RunIOBuilderStep0[F, Ctx]
}
