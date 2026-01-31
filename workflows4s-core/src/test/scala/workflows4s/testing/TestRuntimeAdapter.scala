package workflows4s.testing

import cats.Id
import cats.effect.IO
import cats.effect.unsafe.IORuntime
import cats.effect.unsafe.implicits.global
import com.typesafe.scalalogging.StrictLogging
import workflows4s.runtime.*
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine
import workflows4s.runtime.registry.InMemoryWorkflowRegistry
import workflows4s.wio.*

// Adapt various runtimes to a single interface for tests
trait TestRuntimeAdapter[Ctx <: WorkflowContext] extends StrictLogging {

  protected val knockerUpper             = RecordingKnockerUpper()
  val clock: TestClock                   = TestClock()
  val registry: InMemoryWorkflowRegistry = InMemoryWorkflowRegistry(clock).unsafeRunSync()

  val engine: WorkflowInstanceEngine = WorkflowInstanceEngine.default(knockerUpper, registry, clock)

  type Actor <: WorkflowInstance[Id, WCState[Ctx]]

  def runWorkflow(
      workflow: WIO[Any, Nothing, WCState[Ctx], Ctx],
      state: WCState[Ctx],
  ): Actor

  def recover(first: Actor): Actor

  final def executeDueWakup(actor: Actor): Unit = {
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
        workflow: WIO.Initial[Ctx],
        state: WCState[Ctx],
    ): Actor = {
      val runtime = new InMemorySyncRuntime[Ctx](workflow, state, engine, "test")(using IORuntime.global)
      Actor(List(), runtime)
    }

    override def recover(first: Actor): Actor = Actor(first.getEvents, first.runtime)

    case class Actor(events: Seq[WCEvent[Ctx]], runtime: InMemorySyncRuntime[Ctx])
        extends DelegateWorkflowInstance[Id, WCState[Ctx]]
        with EventIntrospection[WCEvent[Ctx]] {
      val delegate: InMemorySyncWorkflowInstance[Ctx] = {
        val inst = runtime.createInstance("")
        inst.recover(events)
        inst
      }

      override def getEvents: Seq[WCEvent[Ctx]]                                                         = delegate.getEvents
      override def getExpectedSignals(includeRedeliverable: Boolean = false): Id[List[SignalDef[?, ?]]] =
        delegate.getExpectedSignals(includeRedeliverable)
    }
  }

  case class InMemory[Ctx <: WorkflowContext]() extends TestRuntimeAdapter[Ctx] {

    override def runWorkflow(
        workflow: WIO.Initial[Ctx],
        state: WCState[Ctx],
    ): Actor = {
      val runtime = InMemoryRuntime.default[Ctx](workflow, state, engine).unsafeRunSync()
      Actor(List(), runtime)
    }

    override def recover(first: Actor): Actor = Actor(first.getEvents, first.runtime)

    case class Actor(events: Seq[WCEvent[Ctx]], runtime: InMemoryRuntime[Ctx])
        extends DelegateWorkflowInstance[Id, WCState[Ctx]]
        with EventIntrospection[WCEvent[Ctx]] {
      val base: InMemoryWorkflowInstance[Ctx]          = {
        val inst = runtime.createInstance("").unsafeRunSync()
        inst.recover(events).unsafeRunSync()
        inst
      }
      val delegate: WorkflowInstance[Id, WCState[Ctx]] = MappedWorkflowInstance(base, [t] => (x: IO[t]) => x.unsafeRunSync())

      override def getEvents: Seq[WCEvent[Ctx]]                                                         = base.getEvents.unsafeRunSync()
      override def getExpectedSignals(includeRedeliverable: Boolean = false): Id[List[SignalDef[?, ?]]] =
        delegate.getExpectedSignals(includeRedeliverable)
    }

  }

}
