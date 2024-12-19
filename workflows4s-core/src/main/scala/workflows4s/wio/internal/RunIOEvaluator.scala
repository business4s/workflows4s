package workflows4s.wio.internal

import cats.effect.IO
import cats.syntax.all.*
import workflows4s.wio.*
import workflows4s.wio.WIO.Timer

import java.time.Instant

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
      state: In,
      initialState: WCState[Ctx],
      now: Instant,
  ) extends Visitor[Ctx, In, Err, Out](wio) {
    override type Result = Option[IO[WCEvent[Ctx]]]

    def onSignal[Sig, Evt, Resp](wio: WIO.HandleSignal[Ctx, In, Out, Err, Sig, Resp, Evt]): Result                     = None
    def onRunIO[Evt](wio: WIO.RunIO[Ctx, In, Err, Out, Evt]): Result                                                   = {
      wio.buildIO(state).map(wio.evtHandler.convert).some
    }
    def onFlatMap[Out1 <: WCState[Ctx], Err1 <: Err](wio: WIO.FlatMap[Ctx, Err1, Err, Out1, Out, In]): Result          = recurse(wio.base, state)
    def onMap[In1, Out1 <: WCState[Ctx]](wio: WIO.Map[Ctx, In1, Err, Out1, In, Out]): Result                           = recurse(wio.base, wio.contramapInput(state))
    def onNoop(wio: WIO.Noop[Ctx]): Result                                                                             = None
    def onNamed(wio: WIO.Named[Ctx, In, Err, Out]): Result                                                             = recurse(wio.base, state) // TODO, should name be preserved?
    def onHandleError[ErrIn, TempOut <: WCState[Ctx]](wio: WIO.HandleError[Ctx, In, Err, Out, ErrIn, TempOut]): Result = recurse(wio.base, state)
    def onHandleErrorWith[ErrIn](wio: WIO.HandleErrorWith[Ctx, In, ErrIn, Out, Err]): Result                           = recurse(wio.base, state)
    def onAndThen[Out1 <: WCState[Ctx]](wio: WIO.AndThen[Ctx, In, Err, Out1, Out]): Result                             = recurse(wio.first, state)
    def onPure(wio: WIO.Pure[Ctx, In, Err, Out]): Result                                                               = None
    def onLoop[Out1 <: WCState[Ctx]](wio: WIO.Loop[Ctx, In, Err, Out1, Out]): Result                                   = recurse(wio.current, state)
    def onFork(wio: WIO.Fork[Ctx, In, Err, Out]): Result                                                               =
      selectMatching(wio, state).flatMap(nextWio => recurse(nextWio, state))

    def onEmbedded[InnerCtx <: WorkflowContext, InnerOut <: WCState[InnerCtx], MappingOutput[_] <: WCState[Ctx]](
        wio: WIO.Embedded[Ctx, In, Err, InnerCtx, InnerOut, MappingOutput],
    ): Result = {
      val newState: WCState[InnerCtx] =
        wio.embedding
          .unconvertState(initialState)
          .getOrElse(
            wio.initialState(state),
          ) // TODO, this is not safe, we will use initial state if the state mapping is incorrect (not symetrical). This will be very hard for the user to diagnose.
      new RunIOVisitor(wio.inner, state, newState, now).run
        .map(_.map(wio.embedding.convertEvent))
    }

    // proceed on interruption will be needed for timeouts
    def onHandleInterruption(wio: WIO.HandleInterruption[Ctx, In, Err, Out]): Result = {
      // we try to get an event
      recurse(wio.interruption.finalWIO, initialState)
        .orElse(recurse(wio.base, state))
    }

    def onTimer(wio: WIO.Timer[Ctx, In, Err, Out]): Result = {
      val started     = WIO.Timer.Started(now)
      val converted   = wio.startedEventHandler.convert(started)
      Some(IO.pure(converted))
    }

    def onAwaitingTime(wio: WIO.AwaitingTime[Ctx, In, Err, Out]): Result = {
      val timeCame = now.plusNanos(1).isAfter(wio.resumeAt)
      Option.when(timeCame)(
        wio.releasedEventHandler.convert(Timer.Released(now)).pure[IO],
      )
    }

    private def recurse[I1, E1, O1 <: WCState[Ctx]](wio: WIO[I1, E1, O1, Ctx], s: I1): Option[IO[WCEvent[Ctx]]] =
      new RunIOVisitor(wio, s, initialState, now).run
  }

}
