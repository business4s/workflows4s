package workflow4s.runtime

import java.time.Clock

import cats.effect.unsafe.IORuntime
import workflow4s.wio.{ActiveWorkflow, Interpreter, KnockerUpper, WCEvent, WCState, WIO, WorkflowContext}

object InMemorySyncRuntime {

  def runWorkflow[Ctx <: WorkflowContext, In <: WCState[Ctx]](
      behaviour: WIO[In, Nothing, WCState[Ctx], Ctx],
      state: In,
      clock: Clock = Clock.systemUTC(),
      events: Seq[WCEvent[Ctx]] = Seq(),
  )(using ior: IORuntime): InMemorySyncRunningWorkflow[Ctx] = createWithState[Ctx, In](behaviour, state, state, clock, events)

  // this might need to evolve, we provide initial state in case the input can't be one.
  // its necessary because (theoretically) state can be queried before any successful execution.
  def createWithState[Ctx <: WorkflowContext, In](
      behaviour: WIO[In, Nothing, WCState[Ctx], Ctx],
      input: In,
      state: WCState[Ctx],
      clock: Clock = Clock.systemUTC(),
      events: Seq[WCEvent[Ctx]] = List(),
  )(using ior: IORuntime): InMemorySyncRunningWorkflow[Ctx] = {
    val activeWf: ActiveWorkflow.ForCtx[Ctx] =
      ActiveWorkflow(behaviour.transformInput[Any](_ => input), state)(new Interpreter(KnockerUpper.noop))
    val wf                                   = new InMemorySyncRunningWorkflow[Ctx](activeWf, clock)
    wf.recover(events)
    wf
  }

}
