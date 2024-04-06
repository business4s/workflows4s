package workflow4s.wio.internal

import cats.effect.IO
import cats.syntax.all._
import workflow4s.wio.Interpreter.ProceedResponse
import workflow4s.wio._

object ProceedEvaluator {
  import NextWfState.{NewBehaviour, NewValue}

  // runIO required to eliminate Pures showing up after FlatMap
  def proceed[Ctx <: WorkflowContext, StIn, StOut](
      wio: WIO[StIn, Nothing, Ctx#State, Ctx],
      state: StIn,
      runIO: Boolean,
      interpreter: Interpreter[Ctx],
  ): ProceedResponse[Ctx] = {
    val visitor = new ProceedVisitor(wio, state, runIO, interpreter.journal)
    visitor.run match {
      case Some(value) => ProceedResponse.Executed(value.map(wf => wf.toActiveWorkflow(interpreter)))
      case None        => ProceedResponse.Noop()
    }

  }

  private class ProceedVisitor[Ctx <: WorkflowContext, In, Err, Out <: Ctx#State](
      wio: WIO[In, Err, Out, Ctx],
      state: In,
      runIO: Boolean,
      journal: JournalPersistance.Write[Ctx#Event],
  ) extends Visitor[Ctx, In, Err, Out](wio) {
    type NewWf           = NextWfState[Ctx, Err, Out]
    override type Result = Option[IO[NewWf]]

    def onSignal[Sig, Evt, Resp](wio: WIO.HandleSignal[Ctx, In, Out, Err, Sig, Resp, Evt]): Result           = None
    def onRunIO[Evt](wio: WIO.RunIO[Ctx, In, Err, Out, Evt]): Result                                         = {
      if (runIO) {
        (for {
          evt <- wio.buildIO(state)
          _   <- journal.save(wio.evtHandler.convert(evt))
        } yield NewValue(wio.evtHandler.handle(state, evt))).some
      } else None
    }
    def onFlatMap[Out1 <: Ctx#State, Err1 <: Err](wio: WIO.FlatMap[Ctx, Err1, Err, Out1, Out, In]): Result   = {
      val x: Option[IO[NextWfState[Ctx, Err1, Out1]]] = recurse(wio.base, state)
      x.map(_.map(preserveFlatMap(wio, _)))
    }
    def onMap[In1, Out1 <: Ctx#State](wio: WIO.Map[Ctx, In1, Err, Out1, In, Out]): Result                    = {
      recurse(wio.base, wio.contramapInput(state)).map(_.map(preserveMap(wio, _, state)))
    }
    def onHandleQuery[Qr, QrState, Resp](wio: WIO.HandleQuery[Ctx, In, Err, Out, Qr, QrState, Resp]): Result = {
      recurse(wio.inner, state).map(_.map(preserveHandleQuery(wio, _)))
    }
    def onNoop(wio: WIO.Noop[Ctx]): Result                                                                   = None
    def onNamed(wio: WIO.Named[Ctx, In, Err, Out]): Result                                                   = recurse(wio.base, state) // TODO, should name be preserved?
    def onHandleError[ErrIn](wio: WIO.HandleError[Ctx, In, Err, Out, ErrIn]): Result                         = {
      recurse(wio.base, state).map(_.map((newWf: NextWfState[Ctx, ErrIn, Out]) => {
        val casted: NextWfState[Ctx, ErrIn, Out] { type Error = ErrIn } =
          newWf.asInstanceOf[NextWfState[Ctx, ErrIn, Out] { type Error = ErrIn }]
        applyHandleError(wio, casted, state)
      }))
    }
    def onHandleErrorWith[ErrIn](wio: WIO.HandleErrorWith[Ctx, In, ErrIn, Out, Err]): Result                 = {
      recurse(wio.base, state).map(_.map((newWf: NextWfState[Ctx, ErrIn, Out]) => {
        val casted: NextWfState[Ctx, ErrIn, Out] { type Error = ErrIn } =
          newWf.asInstanceOf[NextWfState[Ctx, ErrIn, Out] { type Error = ErrIn }] // TODO casting
        applyHandleErrorWith(wio, casted, state)
      }))
    }
    def onAndThen[Out1 <: Ctx#State](wio: WIO.AndThen[Ctx, In, Err, Out1, Out]): Result                      = {
      recurse(wio.first, state).map(_.map(preserveAndThen(wio, _)))
    }
    def onPure(wio: WIO.Pure[Ctx, In, Err, Out]): Result                                                     = Some(NewValue(wio.value(state)).pure[IO])
    def onDoWhile[Out1 <: Ctx#State](wio: WIO.DoWhile[Ctx, In, Err, Out1, Out]): Result                      = {
      recurse(wio.current, state).map(_.map((newWf: NextWfState[Ctx, Err, Out1]) => {
        applyOnDoWhile(wio, newWf)
      }))
    }
    def onFork(wio: WIO.Fork[Ctx, In, Err, Out]): Result                                                     =
      selectMatching(wio, state).map(nextWio => IO(NewBehaviour(nextWio, Right(state))))

    def onEmbedded[InnerCtx <: WorkflowContext, InnerOut <: InnerCtx#State, MappingOutput[_] <: Ctx#State](wio: WIO.Embedded[Ctx, In, Err, InnerCtx, InnerOut, MappingOutput]): Result = {
      val newJournal = journal.contraMap(wio.embedding.convertEvent)
      new ProceedVisitor(wio.inner, state, runIO, newJournal).run
        .map(_.map(convertResult(wio.embedding, _, state)))
    }

    private def recurse[I1, E1, O1 <: Ctx#State](wio: WIO[I1, E1, O1, Ctx], s: I1): Option[IO[NextWfState[Ctx, E1, O1]]] =
      new ProceedVisitor(wio, s, runIO, journal).run
  }

}
