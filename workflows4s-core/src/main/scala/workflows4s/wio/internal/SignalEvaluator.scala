package workflows4s.wio.internal

import cats.effect.IO
import cats.implicits.toFoldableOps
import com.typesafe.scalalogging.StrictLogging
import workflows4s.wio.*
import workflows4s.wio.Interpreter.SignalResponse
import workflows4s.wio.WIO.HandleInterruption.InterruptionStatus

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
      input: In,
      lastSeenState: WCState[Ctx],
  ) extends Visitor[Ctx, In, Err, Out](wio)
      with StrictLogging {
    override type Result = Option[IO[(WCEvent[Ctx], Resp)]]

    def onSignal[Sig, Evt, Resp](wio: WIO.HandleSignal[Ctx, In, Out, Err, Sig, Resp, Evt]): Result = {
      if (signalDef.id == wio.sigDef.id) {
        val expectedReqOpt = wio.sigDef.reqCt.unapply(req)
        if (expectedReqOpt.isEmpty) {
          logger.warn(
            s"""Request passed to signal handler doesn't have the type expected by the handler. This should not happen, please report it as a bug.
               |Request: ${req}
               |Expected type: ${wio.sigDef.reqCt}
               |""".stripMargin,
          )
        }
        val responseOpt    = expectedReqOpt
          .map(wio.sigHandler.handle(input, _))
          .map(evtIo =>
            for {
              evt   <- evtIo
              result = wio.evtHandler.handle(input, evt)
            } yield wio.evtHandler.convert(evt) -> signalDef.respCt
              .unapply(result._2)
              .getOrElse(
                throw new Exception(
                  s"""The signal response type was different from the one expected based on SignalDef. This is probably a bug, please report it.
                     |Response: ${result._2}
                     |Expected: ${signalDef.respCt}""".stripMargin,
                ),
              ),
          )
        responseOpt
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
    def onExecuted[In1](wio: WIO.Executed[Ctx, Err, Out, In1]): Result                             = None
    def onDiscarded[In](wio: WIO.Discarded[Ctx, In]): Result                                       = None
    def onRecovery[Evt](wio: WIO.Recovery[Ctx, In, Err, Out, Evt]): Result                         = None

    def onFlatMap[Out1 <: WCState[Ctx], Err1 <: Err](wio: WIO.FlatMap[Ctx, Err1, Err, Out1, Out, In]): Result                                  = recurse(wio.base, input)
    def onTransform[In1, Out1 <: State, Err1](wio: WIO.Transform[Ctx, In1, Err1, Out1, In, Out, Err]): Result                                  =
      recurse(wio.base, wio.contramapInput(input))
    def onHandleError[ErrIn, TempOut <: WCState[Ctx]](wio: WIO.HandleError[Ctx, In, Err, Out, ErrIn, TempOut]): Result                         = recurse(wio.base, input)
    def onHandleErrorWith[ErrIn](wio: WIO.HandleErrorWith[Ctx, In, ErrIn, Out, Err]): Result                                                   = {
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
    def onLoop[BodyIn <: WCState[Ctx], BodyOut <: WCState[Ctx], ReturnIn](wio: WIO.Loop[Ctx, In, Err, Out, BodyIn, BodyOut, ReturnIn]): Result = {
      val lastState = wio.history.lastOption.flatMap(_.output.toOption).getOrElse(lastSeenState)
      recurse(wio.current.wio, input, lastState)
    }
    def onFork(wio: WIO.Fork[Ctx, In, Err, Out]): Result                                                                                       =
      selectMatching(wio, input).flatMap(selected => recurse(selected.wio, selected.input))

    def onAndThen[Out1 <: WCState[Ctx]](wio: WIO.AndThen[Ctx, In, Err, Out1, Out]): Result = {
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
        wio: WIO.Embedded[Ctx, In, Err, InnerCtx, InnerOut, MappingOutput],
    ): Result = {
      val newState = wio.embedding.unconvertStateUnsafe(lastSeenState)
      new SignalVisitor(wio.inner, signalDef, req, input, newState).run
        .map(_.map((event, resp) => wio.embedding.convertEvent(event) -> resp))
    }
    def onHandleInterruption(wio: WIO.HandleInterruption[Ctx, In, Err, Out]): Result       = {
      wio.status match {
        case InterruptionStatus.Interrupted                               =>
          recurse(wio.interruption, lastSeenState)
        case InterruptionStatus.TimerStarted | InterruptionStatus.Pending =>
          recurse(wio.interruption, lastSeenState)
            .orElse(recurse(wio.base, input))
      }
    }

    def onParallel[InterimState <: workflows4s.wio.WorkflowContext.State[Ctx]](
        wio: workflows4s.wio.WIO.Parallel[Ctx, In, Err, Out, InterimState],
    ): Result = {
      // We have multiple paths that could handle a signal.
      // We have a lot of options:
      // 1. We could say "only one signal handler for particular signal type can be expected at any point in time" (through linter).
      // 2. Or we could allow for multiple signals of the same type but process only one.
      // 3. Or we change the signature and process all of them.
      // For now we go with 2) but we might need a special EventSignal[Req, Unit] that would be delivered everywhere
      wio.elements.collectFirstSome(elem => recurse(elem.wio, input))
    }

    override def onCheckpoint[Evt, Out1 <: Out](wio: WIO.Checkpoint[Ctx, In, Err, Out1, Evt]): Result = recurse(wio.base, input)

    def recurse[I1, E1, O1 <: WCState[Ctx]](
        wio: WIO[I1, E1, O1, Ctx],
        in: I1,
        state: WCState[Ctx] = lastSeenState,
    ): SignalVisitor[Ctx, Resp, E1, O1, I1, Req]#Result =
      new SignalVisitor(wio, signalDef, req, in, state).run

  }
}
