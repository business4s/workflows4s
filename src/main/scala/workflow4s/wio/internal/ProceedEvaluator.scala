package workflow4s.wio.internal

import cats.effect.IO
import cats.syntax.all._
import workflow4s.wio.Interpreter.ProceedResponse
import workflow4s.wio._

trait ProceedEvaluatorModule extends VisitorModule {
  import c.WIO
  import NextWfState.{NewBehaviour, NewValue}

  object ProceedEvaluator {

    // runIO required to elimintate Pures showing up after FlatMap
    def proceed[StIn, StOut, Err](
        wio: WIO.States[StIn, StOut],
        errOrState: Either[Err, StIn],
        interp: Interpreter,
        runIO: Boolean,
    ): ProceedResponse = {
      errOrState match {
        case Left(value)  => ProceedResponse.Noop()
        case Right(value) =>
          val visitor = new ProceedVisitor(wio, interp, value, runIO)
          visitor.run match {
            case Some(value) => ProceedResponse.Executed(value.map(wf => wf.toActiveWorkflow(interp)))
            case None        => ProceedResponse.Noop()
          }
      }

    }

    private class ProceedVisitor[In, Err, Out](wio: WIO[In, Err, Out], interp: Interpreter, state: In, runIO: Boolean)
        extends Visitor[In, Err, Out](wio) {
      type NewWf           = NextWfState[Err, Out]
      override type Result = Option[IO[NewWf]]

      def onSignal[Sig, Evt, Resp](wio: WIO.HandleSignal[In, Out, Err, Sig, Resp, Evt]): Result = None
      def onRunIO[Evt](wio: WIO.RunIO[In, Err, Out, Evt]): Result= {
        if (runIO) {
          (for {
            evt <- wio.buildIO(state)
            _   <- interp.journal.save(evt)(wio.evtHandler.jw)
          } yield NewValue(wio.evtHandler.handle(state, evt))).some
        } else None
      }
      def onFlatMap[Out1, Err1 <: Err](wio: WIO.FlatMap[Err1, Err, Out1, Out, In]): Result= {
        val x: Option[IO[NextWfState[Err1, Out1]]] = recurse(wio.base, state)
        x.map(_.map(preserveFlatMap(wio, _)))
      }
      def onMap[In1, Out1](wio: WIO.Map[In1, Err, Out1, In, Out]): Result= {
        recurse(wio.base, wio.contramapInput(state)).map(_.map(preserveMap(wio, _, state)))
      }
      def onHandleQuery[Qr, QrState, Resp](wio: WIO.HandleQuery[In, Err, Out, Qr, QrState, Resp]): Result = {
        recurse(wio.inner, state).map(_.map(preserveHandleQuery(wio, _)))
      }
      def onNoop(wio: WIO.Noop): Result= None
      def onNamed(wio: WIO.Named[In, Err, Out]): Result = recurse(wio.base, state) // TODO, should name be preserved?
      def onHandleError[ErrIn](wio: WIO.HandleError[In, Err, Out, ErrIn]): Result = {
        recurse(wio.base, state).map(_.map((newWf: NextWfState[ErrIn, Out]) => {
          val casted: NextWfState[ErrIn, Out] { type Error = ErrIn } =
            newWf.asInstanceOf[NextWfState[ErrIn, Out] { type Error = ErrIn }]
          applyHandleError(wio, casted, state)
        }))
      }
      def onHandleErrorWith[ErrIn](wio: WIO.HandleErrorWith[In, ErrIn, Out, Err]): Result = {
        recurse(wio.base, state).map(_.map((newWf: NextWfState[ErrIn, Out]) => {
          val casted: NextWfState[ErrIn, Out] { type Error = ErrIn } =
            newWf.asInstanceOf[NextWfState[ErrIn, Out] { type Error = ErrIn }] // TODO casting
          applyHandleErrorWith(wio, casted, state)
        }))
      }
      def onAndThen[Out1](wio: WIO.AndThen[In, Err, Out1, Out]): Result = {
        recurse(wio.first, state).map(_.map(preserveAndThen(wio, _)))
      }
      def onPure(wio: WIO.Pure[In, Err, Out]): Result = Some(NewValue(wio.value(state)).pure[IO])
      def onDoWhile[Out1](wio: WIO.DoWhile[In, Err, Out1, Out]): Result = {
        recurse(wio.current, state).map(_.map((newWf: NextWfState[Err, Out1]) => {
          applyOnDoWhile(wio, newWf)
        }))
      }
      def onFork(wio: WIO.Fork[In, Err, Out]): Result =
        selectMatching(wio, state).map(nextWio => IO(NewBehaviour(nextWio, Right(state))))

      private def recurse[I1, E1, O1](wio: WIO[I1, E1, O1], s: I1): Option[IO[NextWfState[E1, O1]]] =
        new ProceedVisitor(wio, interp, s, runIO).run
    }

  }
}
