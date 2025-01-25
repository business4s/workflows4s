package workflows4s.wio.internal

import cats.effect.IO
import workflows4s.wio.*
import workflows4s.wio.Interpreter.SignalResponse
import workflows4s.wio.WIO.HandleInterruption.InterruptionStatus
import workflows4s.wio.model.WIOId

object SignalEvaluator {

  def handleSignal[Ctx <: WorkflowContext, Req, Resp, In <: WCState[Ctx], Out <: WCState[Ctx]](
      signalDef: SignalDef[Req, Resp],
      req: Req,
      wio: WIO[In, Nothing, Out, Ctx],
      state: In,
  ): SignalResponse[Ctx, Resp] = {
    val visitor = new SignalVisitor(wio, signalDef, req, state, state, WIOId.root)
    visitor.run
      .map(SignalResponse.Ok(_))
      .getOrElse(SignalResponse.UnexpectedSignal())
  }

  private class SignalVisitor[Ctx <: WorkflowContext, Resp, Err, Out <: WCState[Ctx], In, Req](
      wio: WIO[In, Err, Out, Ctx],
      signalDef: SignalDef[Req, Resp],
      req: Req,
      input: In,
      lastSeenState: WCState[Ctx],
      id: WIOId,
  ) extends Visitor[Ctx, In, Err, Out](wio, id) {
    override type Result = Option[IO[(WCEvent[Ctx], Resp)]]

    def onSignal[Sig, Evt, Resp](wio: WIO.HandleSignal[Ctx, In, Out, Err, Sig, Resp, Evt]): Result = {
      if (signalDef.id == wio.sigDef.id) {
        wio.sigHandler
          .run(signalDef)(req, input)
          .map(ioOpt =>
            for {
              evt   <- ioOpt
              result = wio.evtHandler.handle(input, evt)
            } yield wio.evtHandler.convert(evt) -> signalDef.respCt.unapply(result._2).get, // TODO .get is unsafe
          )
      } else None
    }
    def onRunIO[Evt](wio: WIO.RunIO[Ctx, In, Err, Out, Evt]): Result                               = None
    def onNoop(wio: WIO.End[Ctx]): Result                                                          = None
    def onPure(wio: WIO.Pure[Ctx, In, Err, Out]): Result                                           = None
    def onTimer(wio: WIO.Timer[Ctx, In, Err, Out]): Result                                         = None
    // we could have "operational" signal that triggers the release?
    // the problem is identifying the timer, but we could parametrize the signal request with time, so its
    // "release timers as if current time was time communicated in the signal"
    def onAwaitingTime(wio: WIO.AwaitingTime[Ctx, In, Err, Out]): Result                           = None
    def onExecuted(wio: WIO.Executed[Ctx, Err, Out]): Result                                       = None
    def onDiscarded[In](wio: WIO.Discarded[Ctx, In]): Result                                       = None

    def onFlatMap[Out1 <: WCState[Ctx], Err1 <: Err](wio: WIO.FlatMap[Ctx, Err1, Err, Out1, Out, In]): Result          = recurse(wio.base, input, 0)
    def onTransform[In1, Out1 <: State, Err1](wio: WIO.Transform[Ctx, In1, Err1, Out1, In, Out, Err]): Result          =
      recurse(wio.base, wio.contramapInput(input), 0)
    def onNamed(wio: WIO.Named[Ctx, In, Err, Out]): Result                                                             = recurse(wio.base, input, 0)
    def onHandleError[ErrIn, TempOut <: WCState[Ctx]](wio: WIO.HandleError[Ctx, In, Err, Out, ErrIn, TempOut]): Result = recurse(wio.base, input, 0)
    def onHandleErrorWith[ErrIn](wio: WIO.HandleErrorWith[Ctx, In, ErrIn, Out, Err]): Result                           = {
      wio.base.asExecuted match {
        case Some(baseExecuted) =>
          baseExecuted.output match {
            case Left(err) => recurse(wio.handleError, (lastSeenState, err), 1)
            case Right(_)  => ??? // TODO better error, we should never reach here
          }
        case None               => recurse(wio.base, input, 0)
      }
    }
    def onLoop[Out1 <: WCState[Ctx]](wio: WIO.Loop[Ctx, In, Err, Out1, Out]): Result                                   = recurse(wio.current, input, ???)
    def onFork(wio: WIO.Fork[Ctx, In, Err, Out]): Result                                                               = selectMatching(wio, input).flatMap((nextWio, idx) => recurse(nextWio, input, idx))

    def onAndThen[Out1 <: WCState[Ctx]](wio: WIO.AndThen[Ctx, In, Err, Out1, Out]): Result = {
      wio.first.asExecuted match {
        case Some(firstExecuted) =>
          firstExecuted.output match {
            case Left(_)      =>
              // This should not happen, whole AndThen should be marked as executed and never entered
              ??? // TODO better exception
            case Right(value) => recurse(wio.second, value, 1)
          }
        case None                => recurse(wio.first, input, 0)
      }
    }

    def onEmbedded[InnerCtx <: WorkflowContext, InnerOut <: WCState[InnerCtx], MappingOutput[_] <: WCState[Ctx]](
        wio: WIO.Embedded[Ctx, In, Err, InnerCtx, InnerOut, MappingOutput],
    ): Result = {
      val newState =
        wio.embedding
          .unconvertState(lastSeenState)
          .getOrElse(
            wio.initialState(input),
          ) // TODO, this is not safe, we will use initial state if the state mapping is incorrect (not symetrical). This will be very hard for the user to diagnose.
      new SignalVisitor(wio.inner, signalDef, req, input, newState, id.child(0)).run
        .map(_.map((event, resp) => wio.embedding.convertEvent(event) -> resp))
    }
    def onHandleInterruption(wio: WIO.HandleInterruption[Ctx, In, Err, Out]): Result       = {
      wio.status match {
        case InterruptionStatus.Interrupted                               =>
          recurse(wio.interruption, lastSeenState, 1)
        case InterruptionStatus.TimerStarted | InterruptionStatus.Pending =>
          recurse(wio.interruption, lastSeenState, 1)
            .orElse(recurse(wio.base, input, 0))
      }
    }

    def recurse[I1, E1, O1 <: WCState[Ctx]](wio: WIO[I1, E1, O1, Ctx], s: I1, idx: Int): SignalVisitor[Ctx, Resp, E1, O1, I1, Req]#Result =
      new SignalVisitor(wio, signalDef, req, s, lastSeenState, id.child(0)).run

  }
}
