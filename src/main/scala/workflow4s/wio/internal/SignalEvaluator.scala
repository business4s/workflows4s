package workflow4s.wio.internal

import cats.effect.IO
import workflow4s.wio.Interpreter.{SignalResponse, Visitor}
import workflow4s.wio.NextWfState.NewValue
import workflow4s.wio.{Interpreter, NextWfState, SignalDef, WIO}

object SignalEvaluator {

  def handleSignal[Req, Resp, StIn, StOut, Err](
      signalDef: SignalDef[Req, Resp],
      req: Req,
      wio: WIO[Err, Any, StIn, StOut],
      state: Either[Err, StIn],
      interp: Interpreter,
  ): SignalResponse[Resp] = {
    val visitor = new SignalVisitor(wio, interp, signalDef, req, state.toOption.get)
    visitor.run
      .map(wfIO => wfIO.map({ case (wf, resp) => wf.toActiveWorkflow(interp) -> resp }))
      .map(SignalResponse.Ok(_))
      .getOrElse(SignalResponse.UnexpectedSignal())
  }

  private class SignalVisitor[Resp, Err, Out, StIn, StOut, Req](
      wio: WIO[Err, Out, StIn, StOut],
      interp: Interpreter,
      signalDef: SignalDef[Req, Resp],
      req: Req,
      state: StIn,
  ) extends Visitor[Err, Out, StIn, StOut](wio) {
    type NewWf                   = NextWfState[Err, Out, StOut]
    override type DispatchResult = Option[IO[(NewWf, Resp)]]

    def onSignal[Sig, Evt, Resp](wio: WIO.HandleSignal[Sig, StIn, StOut, Evt, Out, Err, Resp]): DispatchResult = {
      wio.sigHandler
        .run(signalDef)(req, state)
        .map(ioOpt =>
          for {
            evt   <- ioOpt
            _     <- interp.journal.save(evt)(wio.evtHandler.jw)
            result = wio.evtHandler.handle(state, evt)
          } yield NewValue(result.map({ case (s, o) => (s, o) })) -> signalDef.respCt.unapply(wio.getResp(state, evt)).get, // TODO .get is unsafe
        )
    }

    def onRunIO[Evt](wio: WIO.RunIO[StIn, StOut, Evt, Out, Err]): DispatchResult = None

    def onFlatMap[Out1, StOut1, Err1 <: Err](wio: WIO.FlatMap[Err1, Err, Out1, Out, StIn, StOut1, StOut]): DispatchResult = {
      recurse(wio.base, state).map(_.map({ case (wf, resp) => preserveFlatMap(wio, wf) -> resp }))
    }

    override def onAndThen[Out1, StOut1](wio: WIO.AndThen[Err, Out1, Out, StIn, StOut1, StOut]): DispatchResult = {
      recurse(wio.first, state).map(_.map({ case (wf, resp) => preserveAndThen(wio, wf) -> resp }))
    }

    def onMap[Out1, StIn1, StOut1](wio: WIO.Map[Err, Out1, Out, StIn1, StIn, StOut1, StOut]): DispatchResult = {
      recurse(wio.base, wio.contramapState(state)).map(_.map({ case (wf, resp) => preserveMap(wio, wf, state) -> resp }))
    }

    def onHandleQuery[Qr, QrSt, Resp](wio: WIO.HandleQuery[Err, Out, StIn, StOut, Qr, QrSt, Resp]): DispatchResult = {
      recurse(wio.inner, state).map(_.map({ case (wf, resp) => preserveHandleQuery(wio, wf) -> resp }))
    }

    def onNoop(wio: WIO.Noop): DispatchResult = None

    override def onNamed(wio: WIO.Named[Err, Out, StIn, StOut]): DispatchResult                           = recurse(wio.base, state)
    override def onPure(wio: WIO.Pure[Err, Out, StIn, StOut]): DispatchResult                             = None
    override def onHandleError[ErrIn](wio: WIO.HandleError[Err, Out, StIn, StOut, ErrIn]): DispatchResult =
      recurse(wio.base, state).map(_.map({ case (wf, resp) =>
        val casted: NextWfState[ErrIn, Out, StOut] { type Error = ErrIn } = wf.asInstanceOf[NextWfState[ErrIn, Out, StOut] { type Error = ErrIn }]
        applyHandleError(wio, casted, state) -> resp
      }))
    override def onHandleErrorWith[ErrIn, HandlerStateIn >: StIn, BaseOut >: Out](
        wio: WIO.HandleErrorWith[Err, BaseOut, StIn, StOut, ErrIn, HandlerStateIn, Out],
    ): DispatchResult                                                                                     =
      recurse(wio.base, state).map(_.map({ case (wf, resp) =>
        val casted: NextWfState[ErrIn, Out, StOut] { type Error = ErrIn } =
          wf.asInstanceOf[NextWfState[ErrIn, Out, StOut] { type Error = ErrIn }] // TODO casting
        applyHandleErrorWith(wio, casted, state) -> resp
      }))

    def recurse[E1, O1, StIn1, SOut1](wio: WIO[E1, O1, StIn1, SOut1], s: StIn1): SignalVisitor[Resp, E1, O1, StIn1, SOut1, Req]#DispatchResult =
      new SignalVisitor(wio, interp, signalDef, req, s).run

  }
}
