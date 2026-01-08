package workflows4s.testing

import cats.Id
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.typesafe.scalalogging.StrictLogging
import workflows4s.cats.CatsEffect.given
import workflows4s.runtime.*
import workflows4s.runtime.instanceengine.{Effect, WorkflowInstanceEngine}
import workflows4s.runtime.registry.InMemoryWorkflowRegistry
import workflows4s.wio.*

// Adapt various runtimes to a single interface for tests
// Works with workflows that use the context's effect type (typically IO or Id)
trait TestRuntimeAdapter[Ctx <: WorkflowContext] extends StrictLogging {

  protected val knockerUpper                 = RecordingKnockerUpper()
  val clock: TestClock                       = TestClock()
  val registry: InMemoryWorkflowRegistry[IO] = InMemoryWorkflowRegistry[IO](clock).unsafeRunSync()

  val engine: WorkflowInstanceEngine[IO]   = WorkflowInstanceEngine.default(knockerUpper, registry, clock)
  val idEngine: WorkflowInstanceEngine[Id] = {
    given Effect[Id] = Effect.idEffect
    WorkflowInstanceEngine
      .builder[Id]
      .withJavaTime(clock)
      .withWakeUps(knockerUpper.asId)
      .withoutRegistering
      .withGreedyEvaluation
      .withLogging
      .get
  }

  type Actor <: WorkflowInstance[Id, WCState[Ctx]]

  // Accept IO workflows (most common case) and handle internally
  def runWorkflow(
      workflow: WIO.Initial[IO, Ctx],
      state: WCState[Ctx],
  ): Actor

  def recover(first: Actor): Actor

  final def executeDueWakeup(actor: Actor): Unit = {
    val wakeup = knockerUpper.lastRegisteredWakeup(actor.id)
    logger.debug(s"Executing due wakeup for actor ${actor.id}. Last registered wakeup: ${wakeup}")
    if wakeup.exists(_.isBefore(clock.instant()))
    then actor.wakeup()
  }

}

object TestRuntimeAdapter {

  trait EventIntrospection[Event] {
    def getEvents: Seq[Event]
  }

  case class InMemorySync[Ctx <: WorkflowContext]() extends TestRuntimeAdapter[Ctx] {

    override def runWorkflow(
        workflow: WIO.Initial[IO, Ctx],
        state: WCState[Ctx],
    ): Actor = {
      // SAFETY: This cast is safe because:
      // 1. WIO structure is effect-polymorphic at runtime - only the type parameter differs
      // 2. Test workflows use synchronous operations (e.g., IO.unit.unsafeRunSync() in TestUtils)
      // 3. This adapter is specifically for testing synchronous execution of workflows
      // 4. The Id effect executes operations synchronously, matching the test workflow behavior
      given Effect[Id] = Effect.idEffect
      val idWorkflow   = workflow.asInstanceOf[WIO.Initial[Id, Ctx]]
      val runtime      = InMemoryRuntime.create[Id, Ctx](idWorkflow, state, idEngine, "test")
      Actor(List(), runtime)
    }

    override def recover(first: Actor): Actor = Actor(first.getEvents, first.runtime)

    case class Actor(events: Seq[WCEvent[Ctx]], runtime: InMemoryRuntime[Id, Ctx])
        extends DelegateWorkflowInstance[Id, WCState[Ctx]]
        with EventIntrospection[WCEvent[Ctx]] {
      val delegate: InMemoryWorkflowInstance[Id, Ctx] = {
        val inst = runtime.createInMemoryInstance("")
        inst.recover(events)
        inst
      }

      override def getEvents: Seq[WCEvent[Ctx]]                  = delegate.getEvents
      override def getExpectedSignals: Id[List[SignalDef[?, ?]]] = delegate.getExpectedSignals
    }
  }

  case class InMemory[Ctx <: WorkflowContext]() extends TestRuntimeAdapter[Ctx] {

    override def runWorkflow(
        workflow: WIO.Initial[IO, Ctx],
        state: WCState[Ctx],
    ): Actor = {
      val runtime = InMemoryRuntime.create[IO, Ctx](workflow, state, engine).unsafeRunSync()
      Actor(List(), runtime)
    }

    override def recover(first: Actor): Actor = Actor(first.getEvents, first.runtime)

    case class Actor(events: Seq[WCEvent[Ctx]], runtime: InMemoryRuntime[IO, Ctx])
        extends DelegateWorkflowInstance[Id, WCState[Ctx]]
        with EventIntrospection[WCEvent[Ctx]] {
      val base: InMemoryWorkflowInstance[IO, Ctx]      = {
        val inst = runtime.createInMemoryInstance("").unsafeRunSync()
        inst.recover(events).unsafeRunSync()
        inst
      }
      val delegate: WorkflowInstance[Id, WCState[Ctx]] = MappedWorkflowInstance(base, [t] => (x: IO[t]) => x.unsafeRunSync())

      override def getEvents: Seq[WCEvent[Ctx]]                  = base.getEvents.unsafeRunSync()
      override def getExpectedSignals: Id[List[SignalDef[?, ?]]] = delegate.getExpectedSignals
    }

  }

}
