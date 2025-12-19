package workflows4s.testing

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.typesafe.scalalogging.StrictLogging
import workflows4s.runtime.*
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine
import workflows4s.runtime.registry.InMemoryWorkflowRegistry
import workflows4s.wio.*

/** Test adapter for runtimes that use IO effect type internally (Pekko, Doobie). Unlike TestRuntimeAdapter which uses Id, this trait works with IO
  * workflows directly.
  */
trait IOTestRuntimeAdapter[Ctx <: WorkflowContext] extends StrictLogging {

  protected val knockerUpper             = RecordingKnockerUpper()
  val clock: TestClock                   = TestClock()
  val registry: InMemoryWorkflowRegistry = InMemoryWorkflowRegistry(clock).unsafeRunSync()

  val engine: WorkflowInstanceEngine[IO] = WorkflowInstanceEngine.default(knockerUpper, registry, clock)

  type Actor <: WorkflowInstance[IO, WCState[Ctx]]

  def runWorkflow(
      workflow: WIO.Initial[IO, Ctx],
      state: WCState[Ctx],
  ): Actor

  def recover(first: Actor): Actor

  final def executeDueWakeup(actor: Actor): Unit = {
    val wakeup = knockerUpper.lastRegisteredWakeup(actor.id)
    logger.debug(s"Executing due wakeup for actor ${actor.id}. Last registered wakeup: ${wakeup}")
    if wakeup.exists(_.isBefore(clock.instant()))
    then actor.wakeup().unsafeRunSync()
  }

}

object IOTestRuntimeAdapter {

  trait EventIntrospection[Event] {
    def getEvents: Seq[Event]
  }

  /** InMemory implementation of IOTestRuntimeAdapter for testing IO-based concurrency.
    */
  case class InMemory[Ctx <: WorkflowContext]() extends IOTestRuntimeAdapter[Ctx] {

    override def runWorkflow(
        workflow: WIO.Initial[IO, Ctx],
        state: WCState[Ctx],
    ): Actor = {
      val runtime = InMemoryRuntime.create[IO, Ctx](workflow, state, engine)
      val inst    = runtime.createInstance("").unsafeRunSync().asInstanceOf[InMemoryWorkflowInstance[IO, Ctx]]
      Actor(List(), inst, runtime)
    }

    override def recover(first: Actor): Actor = {
      val recovered = first.runtime.createInstance("").unsafeRunSync().asInstanceOf[InMemoryWorkflowInstance[IO, Ctx]]
      val events    = first.getEvents
      recovered.recover(events).unsafeRunSync()
      Actor(events, recovered, first.runtime)
    }

    case class Actor(events: Seq[WCEvent[Ctx]], instance: InMemoryWorkflowInstance[IO, Ctx], runtime: InMemoryRuntime[IO, Ctx])
        extends DelegateWorkflowInstance[IO, WCState[Ctx]]
        with EventIntrospection[WCEvent[Ctx]] {
      val delegate: WorkflowInstance[IO, WCState[Ctx]] = instance

      override def getEvents: Seq[WCEvent[Ctx]]                  = instance.getEvents.unsafeRunSync()
      override def getExpectedSignals: IO[List[SignalDef[?, ?]]] = instance.getExpectedSignals
    }
  }

}
