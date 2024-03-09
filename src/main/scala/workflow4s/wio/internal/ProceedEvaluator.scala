package workflow4s.wio.internal

import cats.effect.IO
import cats.syntax.all._
import workflow4s.wio.Interpreter.{ProceedResponse, Visitor}
import workflow4s.wio.NextWfState.NewValue
import workflow4s.wio._

object ProceedEvaluator {

  // runIO required to elimintate Pures showing up after FlatMap
  def proceed[StIn, StOut, Err](wio: WIO.States[StIn, StOut], errOrState: Either[Err, StIn], interp: Interpreter, runIO: Boolean): ProceedResponse = {
    val visitor = new ProceedVisitor(wio, interp, errOrState.toOption.get, runIO)
    visitor.run match {
      case Some(value) => ProceedResponse.Executed(value.map(wf => wf.toActiveWorkflow(interp)))
      case None        => ProceedResponse.Noop()
    }
  }

  private class ProceedVisitor[Err, Out, StIn, StOut](wio: WIO[Err, Out, StIn, StOut], interp: Interpreter, state: StIn, runIO: Boolean)
      extends Visitor[Err, Out, StIn, StOut](wio) {
    type NewWf                   = NextWfState[Err, Out, StOut]
    override type DispatchResult = Option[IO[NewWf]]

    def onSignal[Sig, Evt, Resp](wio: WIO.HandleSignal[Sig, StIn, StOut, Evt, Out, Err, Resp]): DispatchResult = None
    def onRunIO[Evt](wio: WIO.RunIO[StIn, StOut, Evt, Out, Err]): DispatchResult = {
      if (runIO) {
        (for {
          evt <- wio.buildIO(state)
          _   <- interp.journal.save(evt)(wio.evtHandler.jw)
        } yield NewValue(wio.evtHandler.handle(state, evt))).some
      } else None
    }

    def onFlatMap[Out1, StOut1, Err1 <: Err](wio: WIO.FlatMap[Err1, Err, Out1, Out, StIn, StOut1, StOut]): DispatchResult = {
      recurse(wio.base, state).map(_.map(preserveFlatMap(wio, _)))
    }

    override def onAndThen[Out1, StOut1](wio: WIO.AndThen[Err, Out1, Out, StIn, StOut1, StOut]): DispatchResult = {
      recurse(wio.first, state).map(_.map(preserveAndThen(wio, _)))
    }

    def onMap[Out1, StIn1, StOut1](wio: WIO.Map[Err, Out1, Out, StIn1, StIn, StOut1, StOut]): DispatchResult = {
      recurse(wio.base, wio.contramapState(state)).map(_.map(preserveMap(wio, _, state)))
    }
    def onHandleQuery[Qr, QrSt, Resp](wio: WIO.HandleQuery[Err, Out, StIn, StOut, Qr, QrSt, Resp]): DispatchResult = {
      recurse(wio.inner, state).map(_.map(preserveHandleQuery(wio, _)))
    }
    def onNoop(wio: WIO.Noop): DispatchResult                                                                  = None
    override def onNamed(wio: WIO.Named[Err, Out, StIn, StOut]): DispatchResult                                = recurse(wio.base, state)
    override def onPure(wio: WIO.Pure[Err, Out, StIn, StOut]): DispatchResult                                  = Some(NewValue(wio.value(state)).pure[IO])

    override def onHandleError[ErrIn](wio: WIO.HandleError[Err, Out, StIn, StOut, ErrIn]): DispatchResult = {
      recurse(wio.base, state).map(_.map((newWf: NextWfState[ErrIn, Out, StOut]) => {
        val casted: NextWfState[ErrIn, Out, StOut] { type Error = ErrIn } = newWf.asInstanceOf[NextWfState[ErrIn, Out, StOut] { type Error = ErrIn }]
        applyHandleError(wio, casted)
      }))
    }

    private def recurse[E1, O1, StIn1, SOut1](wio: WIO[E1, O1, StIn1, SOut1], s: StIn1): ProceedVisitor[E1, O1, StIn, SOut1]#DispatchResult =
      new ProceedVisitor(wio, interp, s, runIO).run
  }

}
