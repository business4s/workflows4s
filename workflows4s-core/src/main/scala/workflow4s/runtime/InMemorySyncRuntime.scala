package workflow4s.runtime

import cats.Id
import cats.effect.unsafe.IORuntime
import workflow4s.wio.WIO.Initial
import workflow4s.wio.{ActiveWorkflow, Interpreter, KnockerUpper, WCEvent, WCState, WIO, WorkflowContext}

import java.time.Clock

class InMemorySyncRuntime[Ctx <: WorkflowContext, WorkflowId, Input](
    workflow: Initial[Ctx, Input],
    initialState: Input => WCState[Ctx],
    clock: Clock,
    knockerUpper: KnockerUpper.Factory[WorkflowId],
)(using IORuntime)
    extends WorkflowRuntime[Id, Ctx, WorkflowId, Input] {

  override def createInstance(id: WorkflowId, in: Input): InMemorySyncWorkflowInstance[Ctx] = {
    val activeWf: ActiveWorkflow.ForCtx[Ctx] = ActiveWorkflow(workflow.provideInput(in), initialState(in))(new Interpreter(knockerUpper(id)))
    new InMemorySyncWorkflowInstance[Ctx](activeWf, clock)
  }

}

object InMemorySyncRuntime {
  def default[Ctx <: WorkflowContext, Input](
      workflow: Initial[Ctx, Input],
      initialState: Input => WCState[Ctx],
  ): InMemorySyncRuntime[Ctx, Unit, Input] =
    new InMemorySyncRuntime[Ctx, Unit, Input](workflow, initialState, Clock.systemUTC(), KnockerUpper.noopFactory)(using IORuntime.global)

  def default[Ctx <: WorkflowContext, Input <: WCState[Ctx]](workflow: Initial[Ctx, Input]): InMemorySyncRuntime[Ctx, Unit, Input] =
    default(workflow, identity)
}
