package workflow4s.wio.internal

import cats.effect.IO
import workflow4s.wio.*
import workflow4s.wio.Interpreter.SignalResponse

object SignalEvaluator {

  def handleSignal[Ctx <: WorkflowContext, Req, Resp, In <: WCState[Ctx], Out <: WCState[Ctx]](
      signalDef: SignalDef[Req, Resp],
      req: Req,
      wio: WIO[In, Nothing, Out, Ctx],
      state: In,
  ): SignalResponse[Ctx, Resp] = {
    val visitor = new SignalVisitor(wio, signalDef, req, state, state)
    visitor.run
      .map(SignalResponse.Ok(_))
      .getOrElse(SignalResponse.UnexpectedSignal())
  }

  private class SignalVisitor[Ctx <: WorkflowContext, Resp, Err, Out <: WCState[Ctx], In, Req](
      wio: WIO[In, Err, Out, Ctx],
      signalDef: SignalDef[Req, Resp],
      req: Req,
      state: In,
      initialState: WCState[Ctx],
  ) extends Visitor[Ctx, In, Err, Out](wio) {
    override type Result = Option[IO[(WCEvent[Ctx], Resp)]]

    def onSignal[Sig, Evt, Resp](wio: WIO.HandleSignal[Ctx, In, Out, Err, Sig, Resp, Evt]): Result                     = {
      wio.sigHandler
        .run(signalDef)(req, state)
        .map(ioOpt =>
          for {
            evt   <- ioOpt
            result = wio.evtHandler.handle(state, evt)
          } yield wio.evtHandler.convert(evt) -> signalDef.respCt.unapply(result._2).get, // TODO .get is unsafe
        )
    }
    def onRunIO[Evt](wio: WIO.RunIO[Ctx, In, Err, Out, Evt]): Result                                                   = None
    def onFlatMap[Out1 <: WCState[Ctx], Err1 <: Err](wio: WIO.FlatMap[Ctx, Err1, Err, Out1, Out, In]): Result          = recurse(wio.base, state)
    def onMap[In1, Out1 <: WCState[Ctx]](wio: WIO.Map[Ctx, In1, Err, Out1, In, Out]): Result                           = recurse(wio.base, wio.contramapInput(state))
    def onNoop(wio: WIO.Noop[Ctx]): Result                                                                             = None
    def onNamed(wio: WIO.Named[Ctx, In, Err, Out]): Result                                                             = recurse(wio.base, state)
    def onHandleError[ErrIn, TempOut <: WCState[Ctx]](wio: WIO.HandleError[Ctx, In, Err, Out, ErrIn, TempOut]): Result = recurse(wio.base, state)
    def onHandleErrorWith[ErrIn](wio: WIO.HandleErrorWith[Ctx, In, ErrIn, Out, Err]): Result                           = recurse(wio.base, state)
    def onAndThen[Out1 <: WCState[Ctx]](wio: WIO.AndThen[Ctx, In, Err, Out1, Out]): Result                             = recurse(wio.first, state)
    def onPure(wio: WIO.Pure[Ctx, In, Err, Out]): Result                                                               = None
    def onLoop[Out1 <: WCState[Ctx]](wio: WIO.Loop[Ctx, In, Err, Out1, Out]): Result                                   = recurse(wio.current, state)
    def onFork(wio: WIO.Fork[Ctx, In, Err, Out]): Result                                                               = selectMatching(wio, state).flatMap(nextWio => recurse(nextWio, state))
    def onEmbedded[InnerCtx <: WorkflowContext, InnerOut <: WCState[InnerCtx], MappingOutput[_] <: WCState[Ctx]](
        wio: WIO.Embedded[Ctx, In, Err, InnerCtx, InnerOut, MappingOutput],
    ): Result = {
      val newState =
        wio.embedding
          .unconvertState(initialState)
          .getOrElse(
            wio.initialState(state),
          ) // TODO, this is not safe, we will use initial state if the state mapping is incorrect (not symetrical). This will be very hard for the user to diagnose.
      new SignalVisitor(wio.inner, signalDef, req, state, newState).run
        .map(_.map((event, resp) => wio.embedding.convertEvent(event) -> resp))
    }
    def onHandleInterruption(wio: WIO.HandleInterruption[Ctx, In, Err, Out]): Result                                   = {
      recurse(wio.interruption.finalWIO, initialState)
        .orElse(recurse(wio.base, state))
    }

    def onTimer(wio: WIO.Timer[Ctx, In, Err, Out]): Result               = None
    // we could have signal that triggers the release?
    // problem is identifying the timer, but we could parametrize the signal request with time, so its
    // "release timers as signal time was now"
    def onAwaitingTime(wio: WIO.AwaitingTime[Ctx, In, Err, Out]): Result = None

    def recurse[I1, E1, O1 <: WCState[Ctx]](wio: WIO[I1, E1, O1, Ctx], s: I1): SignalVisitor[Ctx, Resp, E1, O1, I1, Req]#Result =
      new SignalVisitor(wio, signalDef, req, s, initialState).run

  }
}
