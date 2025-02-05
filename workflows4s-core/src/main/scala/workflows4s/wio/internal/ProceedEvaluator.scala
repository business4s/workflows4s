package workflows4s.wio.internal

import java.time.Instant
import cats.syntax.all.*
import workflows4s.wio.*

// For the given workflow tries to move it to next step if possible without executing any side-effecting comptations.
// This is most common in presence of `Pure` or timers awaiting the threshold.
object ProceedEvaluator {

  // runIO required to eliminate Pures showing up after FlatMap
  def proceed[Ctx <: WorkflowContext](
      wio: WIO[Any, Nothing, WCState[Ctx], Ctx],
      state: WCState[Ctx],
      now: Instant,
  ): Response[Ctx] = {
    val visitor: ProceedVisitor[Ctx, Any, Nothing, WCState[Ctx]] = new ProceedVisitor(wio, state, state, now)
    Response(visitor.run.map(_.toActiveWorkflow(state)))
  }

  case class Response[Ctx <: WorkflowContext](newFlow: Option[ActiveWorkflow[Ctx]])

  private class ProceedVisitor[Ctx <: WorkflowContext, In, Err, Out <: WCState[Ctx]](
      wio: WIO[In, Err, Out, Ctx],
      input: In,
      lastSeenState: WCState[Ctx],
      now: Instant,
  ) extends ProceedingVisitor[Ctx, In, Err, Out](wio, input, lastSeenState) {

    def onSignal[Sig, Evt, Resp](wio: WIO.HandleSignal[Ctx, In, Out, Err, Sig, Resp, Evt]): Result = None
    def onRunIO[Evt](wio: WIO.RunIO[Ctx, In, Err, Out, Evt]): Result                               = None
    def onTimer(wio: WIO.Timer[Ctx, In, Err, Out]): Result                                         = None
    def onAwaitingTime(wio: WIO.AwaitingTime[Ctx, In, Err, Out]): Result                           = None
    
    def onPure(wio: WIO.Pure[Ctx, In, Err, Out]): Result =
      WFExecution.complete(wio, wio.value(input), input).some
    
    def onEmbedded[InnerCtx <: WorkflowContext, InnerOut <: WCState[InnerCtx], MappingOutput[_] <: WCState[Ctx]](
        wio: WIO.Embedded[Ctx, In, Err, InnerCtx, InnerOut, MappingOutput],
    ): Result = {
      val newState: WCState[InnerCtx] =
        wio.embedding
          .unconvertState(lastSeenState)
          .getOrElse(
            wio.initialState(input),
          ) // TODO, this is not safe, we will use initial state if the state mapping is incorrect (not symetrical). This will be very hard for the user to diagnose.
      new ProceedVisitor(wio.inner, input, newState, now).run
        .map(convertEmbeddingResult2(wio, _, input))
    }

    def recurse[I1, E1, O1 <: WCState[Ctx]](wio: WIO[I1, E1, O1, Ctx], s: I1): Option[WFExecution[Ctx, I1, E1, O1]] =
      new ProceedVisitor(wio, s, lastSeenState, now).run
  }

}
