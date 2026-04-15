package workflows4s.testing

import cats.{Id, MonadThrow}
import workflows4s.runtime.*
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine
import workflows4s.runtime.registry.InMemoryWorkflowRegistry
import workflows4s.wio.*

case class InMemorySynchronizedTestRuntimeAdapter[F[+_]: MonadThrow, Ctx <: WorkflowContext](
    runSyncFn: [A] => F[A] => A,
)(using ev: LiftWorkflowEffect[Ctx, F])
    extends TestRuntimeAdapter[F, Ctx] {

  override protected val knockerUpper: RecordingKnockerUpper[F] = RecordingKnockerUpper[F]()
  override val clock: TestClock                                 = TestClock()
  override val registry: InMemoryWorkflowRegistry[F]            = InMemoryWorkflowRegistry[F](clock)
  override val engine: WorkflowInstanceEngine[F, Ctx]           =
    WorkflowInstanceEngine.default(knockerUpper, registry, clock)
  override def runSync[A](fa: F[A]): A                          = runSyncFn(fa)

  override def runWorkflow(workflow: WIO.Initial[Ctx], state: WCState[Ctx]): Actor = {
    val runtime = InMemorySynchronizedRuntime.create[F, Ctx](workflow, state, engine, "test")
    Actor(List(), runtime)
  }

  override def recover(first: Actor): Actor = Actor(first.getEvents, first.runtime)

  case class Actor(events: Seq[WCEvent[Ctx]], runtime: InMemorySynchronizedRuntime[F, Ctx])
      extends DelegateWorkflowInstance[Id, WCState[Ctx]]
      with TestRuntimeAdapter.EventIntrospection[WCEvent[Ctx]] {
    private val inst: InMemorySynchronizedWorkflowInstance[F, Ctx] = {
      val i = runSyncFn(runtime.createInstance(""))
      runSyncFn(i.recover(events))
      i
    }
    val delegate: WorkflowInstance[Id, WCState[Ctx]]               = MappedWorkflowInstance(inst, runSyncFn)

    override def getEvents: Seq[WCEvent[Ctx]]                                                         = inst.getEvents
    def recover(events: Seq[WCEvent[Ctx]]): Unit                                                      = runSyncFn(inst.recover(events))
    override def getExpectedSignals(includeRedeliverable: Boolean = false): Id[List[SignalDef[?, ?]]] =
      delegate.getExpectedSignals(includeRedeliverable)
  }
}
