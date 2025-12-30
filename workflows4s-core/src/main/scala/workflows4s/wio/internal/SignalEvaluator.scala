package workflows4s.wio.internal

import com.typesafe.scalalogging.StrictLogging
import workflows4s.wio.*
import workflows4s.wio.WIO.HandleInterruption.InterruptionStatus
import workflows4s.runtime.instanceengine.Effect

object SignalEvaluator {

  def handleSignal[Ctx <: WorkflowContext, Req, Resp, F[_], In, Out <: WCState[Ctx]](
      signalDef: SignalDef[Req, Resp],
      req: Req,
      wio: WIO[F, In, Nothing, Out, Ctx],
      state: In,
      lastSeenState: WCState[Ctx],
  )(using E: Effect[F]): SignalResult[WCEvent[Ctx], Resp, F] = {
    val visitor = new SignalVisitor[Ctx, F, Resp, Nothing, Out, In, Req](wio, signalDef, req, state, lastSeenState)
    SignalResult.fromRaw[WCEvent[Ctx], Resp, F](visitor.run)
  }

  private class SignalVisitor[Ctx <: WorkflowContext, F[_], Resp, Err, Out <: WCState[Ctx], In, Req](
      wio: WIO[F, In, Err, Out, Ctx],
      signalDef: SignalDef[Req, Resp],
      req: Req,
      input: In,
      lastSeenState: WCState[Ctx],
  )(using E: Effect[F])
      extends Visitor[F, Ctx, In, Err, Out](wio)
      with StrictLogging {

    override type Result = Option[F[(WCEvent[Ctx], Resp)]]

    def onSignal[Sig, Evt, RespSig](wio: WIO.HandleSignal[F, Ctx, In, Out, Err, Sig, RespSig, Evt]): Result = {
      if signalDef.id == wio.sigDef.id then {
        val expectedReqOpt = wio.sigDef.reqCt.unapply(req)

        if expectedReqOpt.isEmpty then {
          logger.warn(
            s"""Request passed to signal handler doesn't have the type expected by the handler. This should not happen, please report it as a bug.
               |Request: ${req}
               |Expected type: ${wio.sigDef.reqCt}
               |""".stripMargin,
          )
        }

        expectedReqOpt.map { reqValue =>
          E.map(wio.sigHandler.handle(input, reqValue)) { (evt: Evt) =>
            val result              = wio.evtHandler.handle(input, evt)
            val event: WCEvent[Ctx] = wio.evtHandler.convert(evt)
            val response            = signalDef.respCt.unapply(result._2).getOrElse {
              throw new Exception(
                s"""The signal response type was different from the one expected based on SignalDef. This is probably a bug, please report it.
                   |Response: ${result._2}
                   |Expected: ${signalDef.respCt}""".stripMargin,
              )
            }
            (event, response)
          }
        }
      } else None
    }

    def onRunIO[Evt](wio: WIO.RunIO[F, Ctx, In, Err, Out, Evt]): Result       = None
    def onNoop(wio: WIO.End[F, Ctx]): Result                                  = None
    def onPure(wio: WIO.Pure[F, Ctx, In, Err, Out]): Result                   = None
    def onTimer(wio: WIO.Timer[F, Ctx, In, Err, Out]): Result                 = None
    def onAwaitingTime(wio: WIO.AwaitingTime[F, Ctx, In, Err, Out]): Result   = None
    def onExecuted[In1](wio: WIO.Executed[F, Ctx, Err, Out, In1]): Result     = None
    def onDiscarded[In1](wio: WIO.Discarded[F, Ctx, In1]): Result             = None
    def onRecovery[Evt](wio: WIO.Recovery[F, Ctx, In, Err, Out, Evt]): Result = None

    def onFlatMap[Out1 <: WCState[Ctx], Err1 <: Err](wio: WIO.FlatMap[F, Ctx, Err1, Err, Out1, Out, In]): Result          = recurse(wio.base, input)
    def onHandleError[ErrIn, TempOut <: WCState[Ctx]](wio: WIO.HandleError[F, Ctx, In, Err, Out, ErrIn, TempOut]): Result = recurse(wio.base, input)
    override def onRetry(wio: WIO.Retry[F, Ctx, In, Err, Out]): Result                                                    = recurse(wio.base, input)

    def onTransform[In1, Out1 <: State, Err1](wio: WIO.Transform[F, Ctx, In1, Err1, Out1, In, Out, Err]): Result =
      recurse(wio.base, wio.contramapInput(input))

    def onHandleErrorWith[ErrIn](wio: WIO.HandleErrorWith[F, Ctx, In, ErrIn, Out, Err]): Result = {
      wio.base.asExecuted match {
        case Some(baseExecuted) =>
          baseExecuted.output match {
            case Left(err) => recurse(wio.handleError, (lastSeenState, err))
            case Right(_)  =>
              throw new IllegalStateException(
                "Base was executed but surrounding HandleErrorWith was still entered during evaluation. This is a bug in the library. Please report it to the maintainers.",
              )
          }
        case None               => recurse(wio.base, input)
      }
    }

    def onLoop[BodyIn <: WCState[Ctx], BodyOut <: WCState[Ctx], ReturnIn](wio: WIO.Loop[F, Ctx, In, Err, Out, BodyIn, BodyOut, ReturnIn]): Result = {
      val lastState = wio.history.lastOption.flatMap(_.output.toOption).getOrElse(lastSeenState)
      recurse(wio.current.wio, input, lastState)
    }

    def onFork(wio: WIO.Fork[F, Ctx, In, Err, Out]): Result =
      selectMatching(wio, input).flatMap(selected => recurse(selected.wio, selected.input))

    def onAndThen[Out1 <: WCState[Ctx]](wio: WIO.AndThen[F, Ctx, In, Err, Out1, Out]): Result = {
      wio.first.asExecuted match {
        case Some(firstExecuted) =>
          firstExecuted.output match {
            case Left(_)      =>
              throw new IllegalStateException(
                "First step of AndThen was executed with an error but surrounding AndThen was still entered during evaluation. This is a bug in the library. Please report it to the maintainers.",
              )
            case Right(value) => recurse(wio.second, value, value)
          }
        case None                => recurse(wio.first, input)
      }
    }

    def onEmbedded[InnerCtx <: WorkflowContext, InnerOut <: WCState[InnerCtx], MappingOutput[_ <: WCState[InnerCtx]] <: WCState[Ctx]](
        wio: WIO.Embedded[F, Ctx, In, Err, InnerCtx, InnerOut, MappingOutput],
    ): Result = {
      val newState = wio.embedding.unconvertStateUnsafe(lastSeenState)
      new SignalVisitor[InnerCtx, F, Resp, Err, InnerOut, In, Req](wio.inner, signalDef, req, input, newState).run
        .map(f => E.map(f)((event, resp) => wio.embedding.convertEvent(event) -> resp))
    }

    def onHandleInterruption(wio: WIO.HandleInterruption[F, Ctx, In, Err, Out]): Result = {
      wio.status match {
        case InterruptionStatus.Interrupted                               =>
          recurse(wio.interruption, lastSeenState)
        case InterruptionStatus.TimerStarted | InterruptionStatus.Pending =>
          recurse(wio.interruption, lastSeenState)
            .orElse(recurse(wio.base, input))
      }
    }

    def onParallel[InterimState <: workflows4s.wio.WorkflowContext.State[Ctx]](
        wio: workflows4s.wio.WIO.Parallel[F, Ctx, In, Err, Out, InterimState],
    ): Result = {
      // Manual collectFirstSome without cats dependency
      wio.elements.iterator.map(elem => recurse(elem.wio, input)).collectFirst { case Some(r) => r }
    }

    override def onCheckpoint[Evt, Out1 <: Out](wio: WIO.Checkpoint[F, Ctx, In, Err, Out1, Evt]): Result = recurse(wio.base, input)

    override def onForEach[ElemId, InnerCtx <: WorkflowContext, ElemOut <: WCState[InnerCtx], InterimState <: WCState[Ctx]](
        wio: WIO.ForEach[F, Ctx, In, Err, Out, ElemId, InnerCtx, ElemOut, InterimState],
    ): Result = {
      for {
        unwrapped <- wio.signalRouter.unwrap(signalDef, req, wio.interimState(input))
        elemWioOpt = wio.state(input).get(unwrapped.elem)
        _          = if elemWioOpt.isEmpty then logger.warn(s"Tried to deliver a signal to an unrecognized element ${unwrapped.elem}")
        elemWio   <- elemWioOpt
        result    <- new SignalVisitor[InnerCtx, F, Resp, Err, ElemOut, Any, Req](
                       elemWio.asInstanceOf[WIO[F, Any, Err, ElemOut, InnerCtx]],
                       unwrapped.sigDef.asInstanceOf[SignalDef[Req, Resp]],
                       unwrapped.req.asInstanceOf[Req],
                       (),
                       wio.initialElemState(),
                     ).run
      } yield E.map(result)(x => wio.eventEmbedding.convertEvent(unwrapped.elem, x._1) -> x._2)
    }

    private def recurse[I1, E1, O1 <: WCState[Ctx]](
        wio: WIO[F, I1, E1, O1, Ctx],
        in: I1,
        state: WCState[Ctx] = lastSeenState,
    ): Result = new SignalVisitor[Ctx, F, Resp, E1, O1, I1, Req](wio, signalDef, req, in, state).run
  }
}
