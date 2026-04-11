package workflows4s.testing.cats.effect

import cats.Id
import cats.effect.Async
import workflows4s.runtime.*
import workflows4s.runtime.cats.effect.{InMemoryConcurrentRuntime, InMemoryConcurrentWorkflowInstance}
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine
import workflows4s.testing.{RecordingKnockerUpper, TestClock, TestRuntimeAdapter}
import workflows4s.runtime.registry.InMemoryWorkflowRegistry
import workflows4s.wio.*

case class InMemoryConcurrentTestRuntimeAdapter[F[+_]: {Async, WeakSync}, Ctx <: WorkflowContext](
    runSyncFn: [A] => F[A] => A,
)(using ev: LiftWorkflowEffect[Ctx, F])
    extends TestRuntimeAdapter[F, Ctx] {

  override protected val knockerUpper: RecordingKnockerUpper[F] = RecordingKnockerUpper[F]()
  override val clock: TestClock                                 = TestClock()
  override val registry: InMemoryWorkflowRegistry[F]            = InMemoryWorkflowRegistry[F](clock)
  override val engine: WorkflowInstanceEngine[F, Ctx]           =
    WorkflowInstanceEngine.default(knockerUpper, registry, clock)
  override def runSync[A](fa: F[A]): A                          = runSyncFn(fa)

  override def runWorkflow(
      workflow: WIO.Initial[Ctx],
      state: WCState[Ctx],
  ): Actor = {
    val runtime = runSyncFn(InMemoryConcurrentRuntime.default[F, Ctx](workflow, state, engine))
    Actor(List(), runtime)
  }

  override def recover(first: Actor): Actor = Actor(first.getEvents, first.runtime)

  case class Actor(events: Seq[WCEvent[Ctx]], runtime: InMemoryConcurrentRuntime[F, Ctx])
      extends DelegateWorkflowInstance[Id, WCState[Ctx]]
      with TestRuntimeAdapter.EventIntrospection[WCEvent[Ctx]] {
    val base: InMemoryConcurrentWorkflowInstance[F, Ctx] = {
      val inst = runSyncFn(runtime.createInstance(""))
      runSyncFn(inst.recover(events))
      inst
    }
    val delegate: WorkflowInstance[Id, WCState[Ctx]]     = MappedWorkflowInstance(base, runSyncFn)

    override def getEvents: Seq[WCEvent[Ctx]]                                                         = runSyncFn(base.getEvents)
    override def getExpectedSignals(includeRedeliverable: Boolean = false): Id[List[SignalDef[?, ?]]] =
      delegate.getExpectedSignals(includeRedeliverable)
  }

}
