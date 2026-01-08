package workflows4s.wio.internal

import java.time.Instant
import workflows4s.wio.*
import workflows4s.wio.WIO.HandleInterruption.InterruptionStatus
import workflows4s.wio.WIO.Timer
import workflows4s.runtime.instanceengine.Effect
// Added missing Cats syntax for .some
import cats.syntax.option.*

object RunIOEvaluator {
  def proceed[Ctx <: WorkflowContext, F[_], StIn <: WCState[Ctx]](
      wio: WIO[F, StIn, Nothing, WCState[Ctx], Ctx],
      state: StIn,
      now: Instant,
  )(using E: Effect[F]): WakeupResult[WCEvent[Ctx], F] = {
    val visitor = new RunIOVisitor[Ctx, F, StIn, Nothing, WCState[Ctx]](wio, state, state, now)
    WakeupResult.fromRaw[WCEvent[Ctx], F](visitor.run)
  }

  private class RunIOVisitor[Ctx <: WorkflowContext, F[_], In, Err, Out <: WCState[Ctx]](
      wio: WIO[F, In, Err, Out, Ctx],
      input: In,
      lastSeenState: WCState[Ctx],
      now: Instant,
  )(using E: Effect[F])
      extends Visitor[F, Ctx, In, Err, Out](wio) {

    import Effect.*

    override type Result = Option[F[Either[Instant, WCEvent[Ctx]]]]

    def onExecuted[In1](wio: WIO.Executed[F, Ctx, Err, Out, In1]): Result                             = None
    def onSignal[Sig, Evt, Resp](wio: WIO.HandleSignal[F, Ctx, In, Out, Err, Sig, Resp, Evt]): Result = None
    def onNoop(wio: WIO.End[F, Ctx]): Result                                                          = None
    def onPure(wio: WIO.Pure[F, Ctx, In, Err, Out]): Result                                           = None
    def onDiscarded[In1](wio: WIO.Discarded[F, Ctx, In1]): Result                                     = None
    override def onRecovery[Evt](wio: WIO.Recovery[F, Ctx, In, Err, Out, Evt]): Result                = None

    def onRunIO[Evt](wio: WIO.RunIO[F, Ctx, In, Err, Out, Evt]): Result =
      wio.buildIO(input).map(evt => Right(wio.evtHandler.convert(evt))).some

    def onFlatMap[Out1 <: WCState[Ctx], Err1 <: Err](wio: WIO.FlatMap[F, Ctx, Err1, Err, Out1, Out, In]): Result = recurse(wio.base, input)
    def onTransform[In1, Out1 <: State, Err1](wio: WIO.Transform[F, Ctx, In1, Err1, Out1, In, Out, Err]): Result =
      recurse(wio.base, wio.contramapInput(input))

    def onHandleError[ErrIn, TempOut <: WCState[Ctx]](wio: WIO.HandleError[F, Ctx, In, Err, Out, ErrIn, TempOut]): Result = recurse(wio.base, input)

    def onHandleErrorWith[ErrIn](wio: WIO.HandleErrorWith[F, Ctx, In, ErrIn, Out, Err]): Result = {
      wio.base.asExecuted match {
        case Some(baseExecuted) =>
          baseExecuted.output match {
            case Left(err) => recurse(wio.handleError, (lastSeenState, err))
            case Right(_)  => throw bug("HandleErrorWith entered but base succeeded")
          }
        case None               => recurse(wio.base, input)
      }
    }

    def onAndThen[Out1 <: WCState[Ctx]](wio: WIO.AndThen[F, Ctx, In, Err, Out1, Out]): Result = {
      wio.first.asExecuted match {
        case Some(firstExecuted) =>
          firstExecuted.output match {
            case Left(_)      => throw bug("AndThen entered but first failed")
            case Right(value) => recurse(wio.second, value)
          }
        case None                => recurse(wio.first, input)
      }
    }

    def onLoop[BodyIn <: WCState[Ctx], BodyOut <: WCState[Ctx], ReturnIn](wio: WIO.Loop[F, Ctx, In, Err, Out, BodyIn, BodyOut, ReturnIn]): Result =
      recurse(wio.current.wio, input)

    def onFork(wio: WIO.Fork[F, Ctx, In, Err, Out]): Result =
      selectMatching(wio, input).flatMap(selected => recurse(selected.wio, selected.input))

    def onEmbedded[InnerCtx <: WorkflowContext, InnerOut <: WCState[InnerCtx], MappingOutput[_ <: WCState[InnerCtx]] <: WCState[Ctx]](
        wio: WIO.Embedded[F, Ctx, In, Err, InnerCtx, InnerOut, MappingOutput],
    ): Result = {
      val newState: WCState[InnerCtx] = wio.embedding.unconvertStateUnsafe(lastSeenState)
      new RunIOVisitor(wio.inner, input, newState, now).run
        .map(_.map(_.map(wio.embedding.convertEvent)))
    }

    def onHandleInterruption(wio: WIO.HandleInterruption[F, Ctx, In, Err, Out]): Result = {
      wio.status match {
        case InterruptionStatus.Interrupted                               => recurse(wio.interruption, lastSeenState)
        case InterruptionStatus.TimerStarted | InterruptionStatus.Pending =>
          recurse(wio.interruption, lastSeenState).orElse(recurse(wio.base, input))
      }
    }

    def onTimer(wio: WIO.Timer[F, Ctx, In, Err, Out]): Result = {
      val event = wio.startedEventHandler.convert(WIO.Timer.Started(now))
      Some(E.pure(Right(event)))
    }

    def onAwaitingTime(wio: WIO.AwaitingTime[F, Ctx, In, Err, Out]): Result = {
      val timeCame = now.plusNanos(1).isAfter(wio.resumeAt)
      Option.when(timeCame)(
        E.pure(Right(wio.releasedEventHandler.convert(Timer.Released(now)))),
      )
    }

    def onParallel[InterimState <: WCState[Ctx]](
        wio: WIO.Parallel[F, Ctx, In, Err, Out, InterimState],
    ): Result = wio.elements.iterator.map(elem => recurse(elem.wio, input)).find(_.isDefined).flatten

    override def onCheckpoint[Evt, Out1 <: Out](wio: WIO.Checkpoint[F, Ctx, In, Err, Out1, Evt]): Result = {
      wio.base.asExecuted match {
        case Some(executedBase) =>
          executedBase.output match {
            case Right(baseOut) => wio.genEvent(input, baseOut).map(evt => Right(wio.eventHandler.convert(evt))).some
            case Left(_)        => None
          }
        case None               => recurse(wio.base, input)
      }
    }

    override def onRetry(wio: WIO.Retry[F, Ctx, In, Err, Out]): Result = {
      // For synchronous effects like Id, exceptions are thrown immediately during recurse.
      // We need to catch them at this level to enable retry behavior.
      def handleError(err: Throwable): F[Either[Instant, WCEvent[Ctx]]] =
        wio.onError(err, lastSeenState, now).flatMap {
          case Some(retryTime) => E.pure(Left(retryTime))
          case None            => E.raiseError(err)
        }

      try {
        recurse(wio.base, input).map(f => f.handleErrorWith(handleError))
      } catch {
        // Catch synchronous exceptions (for Id effect) and handle them as retryable errors
        case err: Throwable =>
          Some(handleError(err))
      }
    }

    override def onForEach[ElemId, InnerCtx <: WorkflowContext, ElemOut <: WCState[InnerCtx], InterimState <: WCState[Ctx]](
        wio: WIO.ForEach[F, Ctx, In, Err, Out, ElemId, InnerCtx, ElemOut, InterimState],
    ): Result = {
      wio
        .state(input)
        .iterator
        .map { (elemId, elemWio) =>
          new RunIOVisitor[InnerCtx, F, Any, Err, ElemOut](elemWio, (), wio.initialElemState(), now).run.map((elemId, _))
        }
        .find(_.isDefined)
        .flatten
        .map { case (elemId, f) => f.map(_.map(wio.eventEmbedding.convertEvent(elemId, _))) }
    }

    private def recurse[I1, E1, O1 <: WCState[Ctx]](wio: WIO[F, I1, E1, O1, Ctx], s: I1): Result =
      new RunIOVisitor(wio, s, lastSeenState, now).run

    private def bug(msg: String) = new IllegalStateException(s"$msg. This is a library bug.")
  }
}
