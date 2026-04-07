package workflows4s.testing

import cats.Id
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.typesafe.scalalogging.StrictLogging
import workflows4s.runtime.*
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine
import workflows4s.runtime.registry.InMemoryWorkflowRegistry
import workflows4s.wio.*
import workflows4s.wio.given

// Adapt various runtimes to a single interface for tests
trait TestRuntimeAdapter[Ctx <: WorkflowContext] extends StrictLogging {

  protected val knockerUpper                 = RecordingKnockerUpper[IO]()
  val clock: TestClock                       = TestClock()
  val registry: InMemoryWorkflowRegistry[IO] = InMemoryWorkflowRegistry[IO](clock)

  val engine: WorkflowInstanceEngine[IO] = WorkflowInstanceEngine.default(knockerUpper, registry, clock)

  type Actor <: WorkflowInstance[Id, WCState[Ctx]]

  def runWorkflow(
      workflow: WIO[IO, Any, Nothing, WCState[Ctx], Ctx],
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
        workflow: WIO.Initial[IO, Ctx],
        state: WCState[Ctx],
    ): Actor = {
      val runtime = InMemorySynchronizedRuntime.create[IO, Ctx](workflow, state, engine, "test")
      Actor(List(), runtime)
    }

    override def recover(first: Actor): Actor = Actor(first.getEvents, first.runtime)

    case class Actor(events: Seq[WCEvent[Ctx]], runtime: InMemorySynchronizedRuntime[IO, Ctx])
        extends DelegateWorkflowInstance[Id, WCState[Ctx]]
        with EventIntrospection[WCEvent[Ctx]] {
      private val sync: SynchronizedWorkflowInstance[Ctx] = {
        val inst = runtime.createInstance("").unsafeRunSync()
        inst.recover(events).unsafeRunSync()
        new SynchronizedWorkflowInstance(inst)
      }
      val delegate: WorkflowInstance[Id, WCState[Ctx]]    = sync

      override def getEvents: Seq[WCEvent[Ctx]]                                                         = sync.getEvents
      override def getExpectedSignals(includeRedeliverable: Boolean = false): Id[List[SignalDef[?, ?]]] =
        sync.getExpectedSignals(includeRedeliverable)
    }
  }

}
