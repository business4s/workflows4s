package workflows4s.wio.internal

import java.time.Instant
import cats.effect.IO
import cats.syntax.all.*
import workflows4s.wio.*
import workflows4s.wio.WIO.HandleInterruption.InterruptionStatus
import workflows4s.wio.WIO.Timer

object RunIOEvaluator {
  def proceed[Ctx <: WorkflowContext, StIn <: WCState[Ctx]](
      wio: WIO[StIn, Nothing, WCState[Ctx], Ctx],
      state: StIn,
      now: Instant,
  ): Response[Ctx] = {
    val visitor = new RunIOVisitor(wio, state, state, now)
    Response(visitor.run)
  }

  case class Response[Ctx <: WorkflowContext](event: Option[IO[WCEvent[Ctx]]])

  private class RunIOVisitor[Ctx <: WorkflowContext, In, Err, Out <: WCState[Ctx]](
      wio: WIO[In, Err, Out, Ctx],
      input: In,
      lastSeenState: WCState[Ctx],
      now: Instant,
  ) extends Visitor[Ctx, In, Err, Out](wio) {
    override type Result = Option[IO[WCEvent[Ctx]]]

    def onExecuted[In1](wio: WIO.Executed[Ctx, Err, Out, In1]): Result                             = None
    def onSignal[Sig, Evt, Resp](wio: WIO.HandleSignal[Ctx, In, Out, Err, Sig, Resp, Evt]): Result = None
    def onNoop(wio: WIO.End[Ctx]): Result                                                          = None
    def onPure(wio: WIO.Pure[Ctx, In, Err, Out]): Result                                           = None
    def onDiscarded[In](wio: WIO.Discarded[Ctx, In]): Result                                       = None

    def onRunIO[Evt](wio: WIO.RunIO[Ctx, In, Err, Out, Evt]): Result = wio.buildIO(input).map(wio.evtHandler.convert).some

    def onFlatMap[Out1 <: WCState[Ctx], Err1 <: Err](wio: WIO.FlatMap[Ctx, Err1, Err, Out1, Out, In]): Result          = recurse(wio.base, input)
    def onTransform[In1, Out1 <: State, Err1](wio: WIO.Transform[Ctx, In1, Err1, Out1, In, Out, Err]): Result          =
      recurse(wio.base, wio.contramapInput(input))
    def onHandleError[ErrIn, TempOut <: WCState[Ctx]](wio: WIO.HandleError[Ctx, In, Err, Out, ErrIn, TempOut]): Result = recurse(wio.base, input)
    def onHandleErrorWith[ErrIn](wio: WIO.HandleErrorWith[Ctx, In, ErrIn, Out, Err]): Result                           = {
      wio.base.asExecuted match {
        case Some(baseExecuted) =>
          baseExecuted.output match {
            case Left(err) => recurse(wio.handleError, (lastSeenState, err))
            case Right(_)  => ??? // TODO better error, we should never reach here
          }
        case None               => recurse(wio.base, input)
      }
    }
    def onAndThen[Out1 <: WCState[Ctx]](wio: WIO.AndThen[Ctx, In, Err, Out1, Out]): Result                             = {
      wio.first.asExecuted match {
        case Some(firstExecuted) =>
          firstExecuted.output match {
            case Left(_)      =>
              // This should not happen, whole AndThen should be marked as executed and never entered
              ??? // TODO better exception
            case Right(value) => recurse(wio.second, value)
          }
        case None                => recurse(wio.first, input)
      }
    }

    def onLoop[Out1 <: WCState[Ctx]](wio: WIO.Loop[Ctx, In, Err, Out1, Out]): Result =
      recurse(wio.current, input)

    def onFork(wio: WIO.Fork[Ctx, In, Err, Out]): Result =
      selectMatching(wio, input).flatMap((nextWio, idx) => recurse(nextWio, input))

    def onEmbedded[InnerCtx <: WorkflowContext, InnerOut <: WCState[InnerCtx], MappingOutput[_] <: WCState[Ctx]](
        wio: WIO.Embedded[Ctx, In, Err, InnerCtx, InnerOut, MappingOutput],
    ): Result = {
      val newState: WCState[InnerCtx] =
        wio.embedding
          .unconvertState(lastSeenState)
          .getOrElse(
            wio.initialState(input),
          ) // TODO, this is not safe, we will use initial state if the state mapping is incorrect (not symetrical). This will be very hard for the user to diagnose.
      new RunIOVisitor(wio.inner, input, newState, now).run
        .map(_.map(wio.embedding.convertEvent))
    }

    // proceed on interruption will be needed for timeouts
    def onHandleInterruption(wio: WIO.HandleInterruption[Ctx, In, Err, Out]): Result = {
      wio.status match {
        case InterruptionStatus.Interrupted                               =>
          recurse(wio.interruption, lastSeenState)
        case InterruptionStatus.TimerStarted | InterruptionStatus.Pending =>
          recurse(wio.interruption, lastSeenState)
            .orElse(recurse(wio.base, input))
      }
    }

    def onTimer(wio: WIO.Timer[Ctx, In, Err, Out]): Result = {
      val started   = WIO.Timer.Started(now)
      val converted = wio.startedEventHandler.convert(started)
      Some(IO.pure(converted))
    }

    def onAwaitingTime(wio: WIO.AwaitingTime[Ctx, In, Err, Out]): Result = {
      val timeCame = now.plusNanos(1).isAfter(wio.resumeAt)
      Option.when(timeCame)(
        wio.releasedEventHandler.convert(Timer.Released(now)).pure[IO],
      )
    }

    private def recurse[I1, E1, O1 <: WCState[Ctx]](wio: WIO[I1, E1, O1, Ctx], s: I1): Option[IO[WCEvent[Ctx]]] =
      new RunIOVisitor(wio, s, lastSeenState, now).run
  }

}
