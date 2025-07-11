package workflows4s.testing

import cats.Id
import cats.effect.unsafe.IORuntime
import com.typesafe.scalalogging.StrictLogging
import workflows4s.runtime.registry.{NoOpWorkflowRegistry, WorkflowRegistry}
import workflows4s.runtime.{InMemoryRuntime, InMemorySyncRuntime, InMemorySyncWorkflowInstance, WorkflowInstance, WorkflowInstanceId}
import workflows4s.wio.*
import workflows4s.wio.model.WIOExecutionProgress

// Adapt various runtimes to a single interface for tests
trait TestRuntimeAdapter[Ctx <: WorkflowContext] extends StrictLogging {

  protected val knockerUpper = FakeKnockerUpper()
  val clock: TestClock       = TestClock()

  type Actor <: WorkflowInstance[Id, WCState[Ctx]]

  def runWorkflow(
      workflow: WIO[Any, Nothing, WCState[Ctx], Ctx],
      state: WCState[Ctx],
      registryAgent: WorkflowRegistry.Agent = NoOpWorkflowRegistry.Agent,
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
        registryAgent: WorkflowRegistry.Agent,
    ): Actor = {
      val runtime = new InMemorySyncRuntime[Ctx](workflow, state, clock, knockerUpper, registryAgent, "test")(using IORuntime.global)
      Actor(List(), runtime)
    }

    override def recover(first: Actor): Actor = Actor(first.getEvents, first.runtime)

    case class Actor(events: Seq[WCEvent[Ctx]], runtime: InMemorySyncRuntime[Ctx])
        extends WorkflowInstance[Id, WCState[Ctx]]
        with EventIntrospection[WCEvent[Ctx]] {
      val base: InMemorySyncWorkflowInstance[Ctx] = {
        val inst = runtime.createInstance("")
        inst.recover(events)
        inst
      }

      def id: WorkflowInstanceId                                                                                                            = base.id
      override def getProgress: Id[WIOExecutionProgress[WCState[Ctx]]]                                                                      = base.getProgress
      override def queryState(): Id[WCState[Ctx]]                                                                                           = base.queryState()
      override def deliverSignal[Req, Resp](signalDef: SignalDef[Req, Resp], req: Req): Id[Either[WorkflowInstance.UnexpectedSignal, Resp]] =
        base.deliverSignal(signalDef, req)
      override def wakeup(): Id[Unit]                                                                                                       = base.wakeup()
      override def getEvents: Seq[WCEvent[Ctx]]                                                                                             = base.getEvents
    }
  }

  case class InMemory[Ctx <: WorkflowContext]() extends TestRuntimeAdapter[Ctx] {
    import cats.effect.unsafe.implicits.global
    override def runWorkflow(
        workflow: WIO.Initial[Ctx],
        state: WCState[Ctx],
        registryAgent: WorkflowRegistry.Agent,
    ): Actor = {
      val runtime = InMemoryRuntime.default[Ctx](workflow, state, knockerUpper, clock, registryAgent).unsafeRunSync()
      Actor(List(), runtime)
    }

    override def recover(first: Actor): Actor = Actor(first.getEvents, first.runtime)

    case class Actor(events: Seq[WCEvent[Ctx]], runtime: InMemoryRuntime[Ctx])
        extends WorkflowInstance[Id, WCState[Ctx]]
        with EventIntrospection[WCEvent[Ctx]] {
      val base = {
        val inst = runtime.createInstance("").unsafeRunSync()
        inst.recover(events).unsafeRunSync()
        inst
      }

      def id: WorkflowInstanceId                                                                                                            = base.id
      override def getProgress: Id[WIOExecutionProgress[WCState[Ctx]]]                                                                      = base.getProgress.unsafeRunSync()
      override def queryState(): Id[WCState[Ctx]]                                                                                           = base.queryState().unsafeRunSync()
      override def deliverSignal[Req, Resp](signalDef: SignalDef[Req, Resp], req: Req): Id[Either[WorkflowInstance.UnexpectedSignal, Resp]] =
        base.deliverSignal(signalDef, req).unsafeRunSync()
      override def wakeup(): Id[Unit]                                                                                                       = base.wakeup().unsafeRunSync()
      override def getEvents: Seq[WCEvent[Ctx]]                                                                                             = base.getEvents.unsafeRunSync()
    }

  }

}
