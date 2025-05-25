package workflows4s.testing

import cats.Id
import cats.effect.unsafe.IORuntime
import workflows4s.runtime.registry.{NoOpWorkflowRegistry, WorkflowRegistry}
import workflows4s.runtime.wakeup.NoOpKnockerUpper
import workflows4s.runtime.{InMemoryRuntime, InMemorySyncRuntime, InMemorySyncWorkflowInstance, WorkflowInstance}
import workflows4s.wio.*
import workflows4s.wio.model.WIOExecutionProgress

import java.time.Clock

// Adapt various runtimes to a single interface for tests
trait TestRuntimeAdapter[Ctx <: WorkflowContext, WfId] {

  type Actor <: WorkflowInstance[Id, WCState[Ctx]]

  def runWorkflow(
      workflow: WIO[Any, Nothing, WCState[Ctx], Ctx],
      state: WCState[Ctx],
      clock: Clock,
      registryAgent: WorkflowRegistry.Agent[WfId] = NoOpWorkflowRegistry.Agent,
  ): Actor

  def recover(first: Actor): Actor

}

object TestRuntimeAdapter {

  trait EventIntrospection[Event] {
    def getEvents: Seq[Event]
  }

  case class InMemorySync[Ctx <: WorkflowContext]() extends TestRuntimeAdapter[Ctx, Unit] {
    override def runWorkflow(
        workflow: WIO.Initial[Ctx],
        state: WCState[Ctx],
        clock: Clock,
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
        with EventIntrospection[WCEvent[Ctx]] {
      val base: InMemorySyncWorkflowInstance[Ctx] = {
        val runtime =
          new InMemorySyncRuntime[Ctx, Unit](initialWorkflow, state, clock, NoOpKnockerUpper.Agent, registryAgent)(using
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
        clock: Clock,
        registryAgent: WorkflowRegistry.Agent[Unit],
    ): Actor = {
      Actor(workflow, state, clock, List(), registryAgent)
    }

    override def recover(first: Actor): Actor =
      Actor(first.workflow, first.state, first.clock, first.getEvents, first.registryAgent)

    case class Actor(
        workflow: WIO[Any, Nothing, WCState[Ctx], Ctx],
        state: WCState[Ctx],
        clock: Clock,
        events: Seq[WCEvent[Ctx]],
        registryAgent: WorkflowRegistry.Agent[Unit],
    ) extends WorkflowInstance[Id, WCState[Ctx]]
        with EventIntrospection[WCEvent[Ctx]] {
      import cats.effect.unsafe.implicits.global
      val base = {
        val runtime = InMemoryRuntime.default[Ctx, Unit](workflow, state, NoOpKnockerUpper.Agent, clock, registryAgent).unsafeRunSync()
        val inst    = runtime.createInstance(()).unsafeRunSync()
        inst.recover(events).unsafeRunSync()
        inst
      }

      override def getProgress: Id[WIOExecutionProgress[WCState[Ctx]]]                                                                      = base.getProgress.unsafeRunSync()
      override def queryState(): Id[WCState[Ctx]]                                                                                           = base.queryState().unsafeRunSync()
      override def deliverSignal[Req, Resp](signalDef: SignalDef[Req, Resp], req: Req): Id[Either[WorkflowInstance.UnexpectedSignal, Resp]] =
        base.deliverSignal(signalDef, req).unsafeRunSync()
      override def wakeup(): Id[Unit]                                                                                                       = base.wakeup().unsafeRunSync()
      override def getEvents: Seq[WCEvent[Ctx]]                                                                                             = base.getEvents.unsafeRunSync()
    }

  }

}
