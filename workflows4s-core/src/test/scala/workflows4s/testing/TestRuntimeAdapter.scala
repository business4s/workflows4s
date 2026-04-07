package workflows4s.testing

import cats.{Id, MonadThrow}
import com.typesafe.scalalogging.StrictLogging
import workflows4s.runtime.*
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine
import workflows4s.runtime.registry.InMemoryWorkflowRegistry
import workflows4s.wio.*

// Adapt various runtimes to a single interface for tests
trait TestRuntimeAdapter[F[_], Ctx <: WorkflowContext] extends StrictLogging {

  protected def knockerUpper: RecordingKnockerUpper[F]
  val clock: TestClock
  val registry: InMemoryWorkflowRegistry[F]

  def engine: WorkflowInstanceEngine[F, Ctx]

  def runSync[A](fa: F[A]): A

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

  case class InMemorySync[F[+_]: {MonadThrow, WeakSync}, Ctx <: WorkflowContext](
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
        with EventIntrospection[WCEvent[Ctx]] {
      private val inst: InMemorySynchronizedWorkflowInstance[F, Ctx] = {
        val i = runSyncFn(runtime.createInstance(""))
        runSyncFn(i.recover(events))
        i
      }
      val delegate: WorkflowInstance[Id, WCState[Ctx]]               = MappedWorkflowInstance(inst, runSyncFn)

      override def getEvents: Seq[WCEvent[Ctx]]                                                         = inst.getEvents
      override def getExpectedSignals(includeRedeliverable: Boolean = false): Id[List[SignalDef[?, ?]]] =
        delegate.getExpectedSignals(includeRedeliverable)
    }
  }

}
