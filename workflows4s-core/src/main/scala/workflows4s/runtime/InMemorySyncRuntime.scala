package workflows4s.runtime

import cats.Id
import cats.effect.unsafe.IORuntime
import workflows4s.runtime.wakeup.{KnockerUpper, NoOpKnockerUpper}
import workflows4s.wio.WIO.Initial
import workflows4s.wio.*

import java.time.Clock

class InMemorySyncRuntime[Ctx <: WorkflowContext, WorkflowId](
    workflow: Initial[Ctx],
    initialState: WCState[Ctx],
    clock: Clock,
    knockerUpperAgent: KnockerUpper.Agent[WorkflowId],
)(using IORuntime)
    extends WorkflowRuntime[Id, Ctx, WorkflowId] {

  override def createInstance(id: WorkflowId): InMemorySyncWorkflowInstance[Ctx] = {
    val atomicRef                     = new java.util.concurrent.atomic.AtomicReference[InMemorySyncWorkflowInstance[Ctx]](null)
    val activeWf: ActiveWorkflow[Ctx] = ActiveWorkflow(workflow, initialState, None)
    val instance                      = new InMemorySyncWorkflowInstance[Ctx](activeWf, clock, knockerUpperAgent.curried(id))
    atomicRef.set(instance)
    instance
  }

}

object InMemorySyncRuntime {
  def default[Ctx <: WorkflowContext](
      workflow: Initial[Ctx],
      initialState: WCState[Ctx],
      knockerUpperAgent: KnockerUpper.Agent[Unit] = NoOpKnockerUpper.Agent, // TODO probably no other KU could be used?
  ): InMemorySyncRuntime[Ctx, Unit] =
    new InMemorySyncRuntime[Ctx, Unit](workflow, initialState, Clock.systemUTC(), knockerUpperAgent)(using IORuntime.global)
}
