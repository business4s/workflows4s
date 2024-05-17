package workflow4s.wio.internal

import cats.data.Ior
import cats.effect.IO
import cats.syntax.all.*
import workflow4s.wio.Interpreter.ProceedResponse
import workflow4s.wio.*

import java.time.Instant

// For the given workflow tries to move it to next step if possible without executing any side-effecting comuptations.
// This is most common in presence of `Pure` or timers awaiting the threshold.
object ProceedEvaluator {
  import NextWfState.NewValue

  // runIO required to eliminate Pures showing up after FlatMap
  def proceed[Ctx <: WorkflowContext, StIn <: WCState[Ctx]](
      wio: WIO[StIn, Nothing, WCState[Ctx], Ctx],
      state: StIn,
      interpreter: Interpreter,
      now: Instant,
  ): Response[Ctx] = {
    val visitor = new ProceedVisitor(wio, state, state, now)
    Response(visitor.run.map(_.toActiveWorkflow(interpreter)))
  }

  case class Response[Ctx <: WorkflowContext](newFlow: Option[ActiveWorkflow.ForCtx[Ctx]])

  private class ProceedVisitor[Ctx <: WorkflowContext, In, Err, Out <: WCState[Ctx]](
      wio: WIO[In, Err, Out, Ctx],
      state: In,
      initialState: WCState[Ctx],
      now: Instant,
  ) extends Visitor[Ctx, In, Err, Out](wio) {
    type NewWf           = NextWfState[Ctx, Err, Out]
    override type Result = Option[NewWf]

    def onSignal[Sig, Evt, Resp](wio: WIO.HandleSignal[Ctx, In, Out, Err, Sig, Resp, Evt]): Result                     = None
    def onRunIO[Evt](wio: WIO.RunIO[Ctx, In, Err, Out, Evt]): Result                                                   = None
    def onFlatMap[Out1 <: WCState[Ctx], Err1 <: Err](wio: WIO.FlatMap[Ctx, Err1, Err, Out1, Out, In]): Result          = {
      val x: Option[NextWfState[Ctx, Err1, Out1]] = recurse(wio.base, state)
      x.map(preserveFlatMap(wio, _))
    }
    def onMap[In1, Out1 <: WCState[Ctx]](wio: WIO.Map[Ctx, In1, Err, Out1, In, Out]): Result                           = {
      recurse(wio.base, wio.contramapInput(state)).map(preserveMap(wio, _, state))
    }
    def onNoop(wio: WIO.Noop[Ctx]): Result                                                                             = None
    def onNamed(wio: WIO.Named[Ctx, In, Err, Out]): Result                                                             = recurse(wio.base, state) // TODO, should name be preserved?
    def onHandleError[ErrIn, TempOut <: WCState[Ctx]](wio: WIO.HandleError[Ctx, In, Err, Out, ErrIn, TempOut]): Result = {
      recurse(wio.base, state).map((newWf: NextWfState[Ctx, ErrIn, Out]) => {
        val casted: NextWfState[Ctx, ErrIn, Out] { type Error = ErrIn } =
          newWf.asInstanceOf[NextWfState[Ctx, ErrIn, Out] { type Error = ErrIn }]
        applyHandleError(wio, casted, state)
      })
    }
    def onHandleErrorWith[ErrIn](wio: WIO.HandleErrorWith[Ctx, In, ErrIn, Out, Err]): Result                           = {
      recurse(wio.base, state).map((newWf: NextWfState[Ctx, ErrIn, Out]) => {
        val casted: NextWfState[Ctx, ErrIn, Out] { type Error = ErrIn } =
          newWf.asInstanceOf[NextWfState[Ctx, ErrIn, Out] { type Error = ErrIn }] // TODO casting
        applyHandleErrorWith(wio, casted, initialState)
      })
    }
    def onAndThen[Out1 <: WCState[Ctx]](wio: WIO.AndThen[Ctx, In, Err, Out1, Out]): Result                             = {
      recurse(wio.first, state).map(preserveAndThen(wio, _))
    }
    def onPure(wio: WIO.Pure[Ctx, In, Err, Out]): Result                                                               = Some(NewValue(wio.value(state)))
    def onLoop[Out1 <: WCState[Ctx]](wio: WIO.Loop[Ctx, In, Err, Out1, Out]): Result                                   = {
      recurse(wio.current, state).map(applyLoop(wio, _))
    }
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
      new ProceedVisitor(wio.inner, state, newState, now).run
        .map(convertResult(wio, _, state))
    }

    // proceed on interruption will be needed for timeouts
    def onHandleInterruption(wio: WIO.HandleInterruption[Ctx, In, Err, Out]): Result = {
      def runBase: Result          = recurse(wio.base, state)
        .map(preserveHandleInterruption(wio.interruption, _))
      val dedicatedProceed: Result = wio.interruption.trigger match {
        case x @ WIO.HandleSignal(_, _, _, _)       => None /// no proceeding when waiting for signal
        // type params are not correct
        case x: WIO.Timer[Ctx, In, Err, Out]        => None
        case x: WIO.AwaitingTime[Ctx, In, Err, Out] => recurse(wio.interruption.finalWIO, initialState)
      }
      dedicatedProceed.orElse(runBase)
    }

    def onTimer(wio: WIO.Timer[Ctx, In, Err, Out]): Result               = None
    def onAwaitingTime(wio: WIO.AwaitingTime[Ctx, In, Err, Out]): Result = None

    private def recurse[I1, E1, O1 <: WCState[Ctx]](wio: WIO[I1, E1, O1, Ctx], s: I1): Option[NextWfState[Ctx, E1, O1]] =
      new ProceedVisitor(wio, s, initialState, now).run
  }

}
