package workflow4s.wio.internal

import cats.effect.IO
import cats.syntax.all._
import workflow4s.wio.Interpreter.ProceedResponse
import workflow4s.wio._

class ProceedEvaluator[Ctx <: WorkflowContext](val c: Ctx, interpreter: Interpreter[Ctx]) extends VisitorModule[Ctx] {
  import NextWfState.{NewBehaviour, NewValue}

  // runIO required to eliminate Pures showing up after FlatMap
  def proceed[StIn, StOut](
      wio: Ctx#WIO[StIn, Nothing, StOut],
      state: StIn,
      runIO: Boolean,
  ): ProceedResponse[Ctx] = {
    val visitor = new ProceedVisitor(wio, state, runIO)
    visitor.run match {
      case Some(value) => ProceedResponse.Executed(value.map(wf => wf.toActiveWorkflow(interpreter)))
      case None        => ProceedResponse.Noop()
    }

  }

  private class ProceedVisitor[In, Err, Out](wio: WIO[In, Err, Out], state: In, runIO: Boolean) extends Visitor[In, Err, Out](wio) {
    type NewWf           = NextWfState[Err, Out]
    override type Result = Option[IO[NewWf]]

    def onSignal[Sig, Evt, Resp](wio: WIOC#HandleSignal[In, Out, Err, Sig, Resp, Evt]): Result = None
    def onRunIO[Evt](wio: WIOC#RunIO[In, Err, Out, Evt]): Result                               = {
      if (runIO) {
        (for {
          evt <- wio.buildIO(state)
          _   <- interpreter.journal.save(wio.evtHandler.convert(evt))
        } yield NewValue(wio.evtHandler.handle(state, evt))).some
      } else None
    }
    def onFlatMap[Out1, Err1 <: Err](wio: WIOC#FlatMap[Err1, Err, Out1, Out, In]): Result                 = {
      val x: Option[IO[NextWfState[Err1, Out1]]] = recurse(wio.base, state)
      x.map(_.map(preserveFlatMap(wio, _)))
    }
    def onMap[In1, Out1](wio: WIOC#Map[In1, Err, Out1, In, Out]): Result                                  = {
      recurse(wio.base, wio.contramapInput(state)).map(_.map(preserveMap(wio, _, state)))
    }
    def onHandleQuery[Qr, QrState, Resp](wio: WIOC#HandleQuery[In, Err, Out, Qr, QrState, Resp]): Result  = {
      recurse(wio.inner, state).map(_.map(preserveHandleQuery(wio, _)))
    }
    def onNoop(wio: WIOC#Noop): Result                                                                    = None
    def onNamed(wio: WIOC#Named[In, Err, Out]): Result                                                    = recurse(wio.base, state) // TODO, should name be preserved?
    def onHandleError[ErrIn](wio: WIOC#HandleError[In, Err, Out, ErrIn]): Result                          = {
      recurse(wio.base, state).map(_.map((newWf: NextWfState[ErrIn, Out]) => {
        val casted: NextWfState[ErrIn, Out] { type Error = ErrIn } =
          newWf.asInstanceOf[NextWfState[ErrIn, Out] { type Error = ErrIn }]
        applyHandleError(wio, casted, state)
      }))
    }
    def onHandleErrorWith[ErrIn](wio: WIOC#HandleErrorWith[In, ErrIn, Out, Err]): Result                  = {
      recurse(wio.base, state).map(_.map((newWf: NextWfState[ErrIn, Out]) => {
        val casted: NextWfState[ErrIn, Out] { type Error = ErrIn } =
          newWf.asInstanceOf[NextWfState[ErrIn, Out] { type Error = ErrIn }] // TODO casting
        applyHandleErrorWith(wio, casted, state)
      }))
    }
    def onAndThen[Out1](wio: WIOC#AndThen[In, Err, Out1, Out]): Result                                    = {
      recurse(wio.first, state).map(_.map(preserveAndThen(wio, _)))
    }
    def onPure(wio: WIOC#Pure[In, Err, Out]): Result                                                      = Some(NewValue(wio.value(state)).pure[IO])
    def onDoWhile[Out1](wio: WIOC#DoWhile[In, Err, Out1, Out]): Result                                    = {
      recurse(wio.current, state).map(_.map((newWf: NextWfState[Err, Out1]) => {
        applyOnDoWhile(wio, newWf)
      }))
    }
    def onFork(wio: WIOC#Fork[In, Err, Out]): Result                                                      =
      selectMatching(wio, state).map(nextWio => IO(NewBehaviour(nextWio, Right(state))))

    private def recurse[I1, E1, O1](wio: WIO[I1, E1, O1], s: I1): Option[IO[NextWfState[E1, O1]]] =
      new ProceedVisitor(wio, s, runIO).run
  }

}
