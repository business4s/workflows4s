package workflows4s.testing.cats.effect

import cats.Id
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import workflows4s.runtime.*
import workflows4s.runtime.cats.effect.{InMemoryConcurrentRuntime, InMemoryConcurrentWorkflowInstance}
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine
import workflows4s.testing.TestRuntimeAdapter
import workflows4s.wio.*
import workflows4s.wio.cats.effect.WeakSyncInstances.given

case class InMemoryConcurrentTestRuntimeAdapter[Ctx <: WorkflowContext](
)(using wcEffectMonadThrow: MonadThrowContainer[Ctx], ev: LiftWorkflowEffect[Ctx, IO])
    extends TestRuntimeAdapter[Ctx] {

  override val engine: WorkflowInstanceEngine[IO, Ctx] =
    WorkflowInstanceEngine.default(knockerUpper, registry, clock)

  override def runWorkflow(
      workflow: WIO.Initial[Ctx],
      state: WCState[Ctx],
  ): Actor = {
    val runtime = InMemoryConcurrentRuntime.default[IO, Ctx](workflow, state, engine).unsafeRunSync()
    Actor(List(), runtime)
  }

  override def recover(first: Actor): Actor = Actor(first.getEvents, first.runtime)

  case class Actor(events: Seq[WCEvent[Ctx]], runtime: InMemoryConcurrentRuntime[IO, Ctx])
      extends DelegateWorkflowInstance[Id, WCState[Ctx]]
      with TestRuntimeAdapter.EventIntrospection[WCEvent[Ctx]] {
    val base: InMemoryConcurrentWorkflowInstance[IO, Ctx] = {
      val inst = runtime.createInstance("").unsafeRunSync()
      inst.recover(events).unsafeRunSync()
      inst
    }
    val delegate: WorkflowInstance[Id, WCState[Ctx]]      = MappedWorkflowInstance(base, [t] => (x: IO[t]) => x.unsafeRunSync())

    override def getEvents: Seq[WCEvent[Ctx]]                                                         = base.getEvents.unsafeRunSync()
    override def getExpectedSignals(includeRedeliverable: Boolean = false): Id[List[SignalDef[?, ?]]] =
      delegate.getExpectedSignals(includeRedeliverable)
  }

}
