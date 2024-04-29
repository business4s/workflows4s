package workflow4s.runtime

import cats.effect.unsafe.IORuntime
import workflow4s.wio.{ActiveWorkflow, Interpreter, KnockerUpper, WCEvent, WCState, WIO, WorkflowContext}

import java.time.Clock

object InMemorySyncRuntime {

  def create[Ctx <: WorkflowContext, In <: WCState[Ctx]](
      behaviour: WIO[In, Nothing, WCState[Ctx], Ctx],
      state: In,
      clock: Clock = Clock.systemUTC(),
      events: List[WCEvent[Ctx]] = List(),
  )(implicit ior: IORuntime): InMemorySyncRunningWorkflow[Ctx] = createWithState[Ctx, In](behaviour, state, state, clock, events)

  // this might need to evolve, we provide initial state in case the input can't be one.
  // its needed because (theoretically) state can be queried before any succesfull execution.
  def createWithState[Ctx <: WorkflowContext, In](
      behaviour: WIO[In, Nothing, WCState[Ctx], Ctx],
      input: In,
      state: WCState[Ctx],
      clock: Clock = Clock.systemUTC(),
      events: Seq[WCEvent[Ctx]] = List(),
  )(implicit ior: IORuntime): InMemorySyncRunningWorkflow[Ctx] = {
    val activeWf: ActiveWorkflow.ForCtx[Ctx] =
      ActiveWorkflow(behaviour.transformInput[Any](_ => input), state)(new Interpreter(KnockerUpper.noop))
    val wf                                   = new InMemorySyncRunningWorkflow[Ctx](activeWf, clock)
    wf.recover(events)
    wf
  }

}
