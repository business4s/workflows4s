package workflows4s.testing

import cats.Id
import cats.effect.unsafe.IORuntime
import workflows4s.runtime.registry.{NoOpWorkflowRegistry, WorkflowRegistry}
import workflows4s.runtime.{InMemoryRuntime, InMemorySyncRuntime, InMemorySyncWorkflowInstance, WorkflowInstance}
import workflows4s.testing.TestRuntimeAdapter.Identifiable
import workflows4s.wio.*
import workflows4s.wio.model.WIOExecutionProgress

import java.time.Clock

// Adapt various runtimes to a single interface for tests
trait TestRuntimeAdapter[Ctx <: WorkflowContext, WfId] {

  protected val knockerUpper = FakeKnockerUpper[WfId]()
  val clock: TestClock = TestClock()

  type Actor <: WorkflowInstance[Id, WCState[Ctx]] & Identifiable[WfId]

  def runWorkflow(
      workflow: WIO[Any, Nothing, WCState[Ctx], Ctx],
      state: WCState[Ctx],
      registryAgent: WorkflowRegistry.Agent[WfId] = NoOpWorkflowRegistry.Agent,
  ): Actor

  def recover(first: Actor): Actor

  final def executeDueWakup(actor: Actor): Unit = {
    if knockerUpper.lastRegisteredWakeup(actor.id).exists(_.isBefore(clock.instant()))
    then actor.wakeup()
  }

}

object TestRuntimeAdapter {

  trait Identifiable[WfId] {
    def id: WfId
  }

  trait EventIntrospection[Event] {
    def getEvents: Seq[Event]
  }

  case class InMemorySync[Ctx <: WorkflowContext]() extends TestRuntimeAdapter[Ctx, Unit] {
    override def runWorkflow(
        workflow: WIO.Initial[Ctx],
        state: WCState[Ctx],
        registryAgent: WorkflowRegistry.Agent[Unit],
    ): Actor = Actor(workflow, state, clock, List(), registryAgent)

    override def recover(first: Actor): Actor = {
      Actor(first.initialWorkflow, first.state, first.clock, first.getEvents, first.registryAgent)
    }

    case class Actor(
        initialWorkflow: WIO.Initial[Ctx],
        state: WCState[Ctx],
        clock: Clock,
        events: Seq[WCEvent[Ctx]],
        registryAgent: WorkflowRegistry.Agent[Unit],
    ) extends WorkflowInstance[Id, WCState[Ctx]]
        with EventIntrospection[WCEvent[Ctx]] with Identifiable[Unit] {
      def id = ()
      val base: InMemorySyncWorkflowInstance[Ctx] = {
        val runtime =
          new InMemorySyncRuntime[Ctx, Unit](initialWorkflow, state, clock, knockerUpper, registryAgent)(using
            IORuntime.global,
          )
        val inst    = runtime.createInstance(())
        inst.recover(events)
        inst
      }

      override def getProgress: Id[WIOExecutionProgress[WCState[Ctx]]]                                                                      = base.getProgress
      override def queryState(): Id[WCState[Ctx]]                                                                                           = base.queryState()
      override def deliverSignal[Req, Resp](signalDef: SignalDef[Req, Resp], req: Req): Id[Either[WorkflowInstance.UnexpectedSignal, Resp]] =
        base.deliverSignal(signalDef, req)
      override def wakeup(): Id[Unit]                                                                                                       = base.wakeup()
      override def getEvents: Seq[WCEvent[Ctx]]                                                                                             = base.getEvents
    }
  }

  case class InMemory[Ctx <: WorkflowContext]() extends TestRuntimeAdapter[Ctx, Unit] {
    override def runWorkflow(
        workflow: WIO.Initial[Ctx],
        state: WCState[Ctx],
        registryAgent: WorkflowRegistry.Agent[Unit],
    ): Actor = {
      Actor(workflow, state, List(), registryAgent)
    }

    override def recover(first: Actor): Actor =
      Actor(first.workflow, first.state, first.getEvents, first.registryAgent)

    case class Actor(
        workflow: WIO[Any, Nothing, WCState[Ctx], Ctx],
        state: WCState[Ctx],
        events: Seq[WCEvent[Ctx]],
        registryAgent: WorkflowRegistry.Agent[Unit],
    ) extends WorkflowInstance[Id, WCState[Ctx]]
        with EventIntrospection[WCEvent[Ctx]] with Identifiable[Unit]  {
      import cats.effect.unsafe.implicits.global
      val base = {
        val runtime = InMemoryRuntime.default[Ctx, Unit](workflow, state, knockerUpper, clock, registryAgent).unsafeRunSync()
        val inst    = runtime.createInstance(()).unsafeRunSync()
        inst.recover(events).unsafeRunSync()
        inst
      }

      def id: Unit = ()
      override def getProgress: Id[WIOExecutionProgress[WCState[Ctx]]]                                                                      = base.getProgress.unsafeRunSync()
      override def queryState(): Id[WCState[Ctx]]                                                                                           = base.queryState().unsafeRunSync()
      override def deliverSignal[Req, Resp](signalDef: SignalDef[Req, Resp], req: Req): Id[Either[WorkflowInstance.UnexpectedSignal, Resp]] =
        base.deliverSignal(signalDef, req).unsafeRunSync()
      override def wakeup(): Id[Unit]                                                                                                       = base.wakeup().unsafeRunSync()
      override def getEvents: Seq[WCEvent[Ctx]]                                                                                             = base.getEvents.unsafeRunSync()
    }

  }

}
