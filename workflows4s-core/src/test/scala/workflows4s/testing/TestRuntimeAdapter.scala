package workflows4s.testing

import cats.Id
import com.typesafe.scalalogging.StrictLogging
import workflows4s.effect.Effect
import workflows4s.runtime.*
import workflows4s.runtime.instanceengine.{BasicJavaTimeEngine, GreedyWorkflowInstanceEngine, LoggingWorkflowInstanceEngine, WorkflowInstanceEngine}
import workflows4s.wio.*

// Adapt various runtimes to a single interface for tests
// Uses cats.Id for synchronous testing without cats-effect dependency
trait TestRuntimeAdapter[Ctx <: WorkflowContext] extends StrictLogging {

  protected val knockerUpper: RecordingKnockerUpper[Id] = new RecordingKnockerUpper[Id](using Effect.idEffect)
  val clock: TestClock                                  = TestClock()

  val engine: WorkflowInstanceEngine[Id] = {
    val base   = new BasicJavaTimeEngine[Id](clock)(using Effect.idEffect)
    val greedy = GreedyWorkflowInstanceEngine[Id](base)(using Effect.idEffect)
    new LoggingWorkflowInstanceEngine[Id](greedy)(using Effect.idEffect)
  }

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

  /** Simple in-memory runtime adapter for tests using cats.Id */
  case class InMemorySync[Ctx <: WorkflowContext]() extends TestRuntimeAdapter[Ctx] {

    override def runWorkflow(
        workflow: WIO.Initial[Ctx],
        state: WCState[Ctx],
    ): Actor = {
      val activeWorkflow = ActiveWorkflow(WorkflowInstanceId("test", ""), workflow.provideInput(state), state)
      Actor(List(), activeWorkflow, engine)
    }

    override def recover(first: Actor): Actor = {
      // Replay events to recover state
      val recovered = first.events.foldLeft(first.initialWorkflow) { (wf, event) =>
        engine.processEvent(wf, event.asInstanceOf[WCEvent[Ctx]])(using Effect.idEffect)
      }
      Actor(first.events, recovered, engine)
    }

    case class Actor(
        events: Seq[WCEvent[Ctx]],
        private[TestRuntimeAdapter] val initialWorkflow: ActiveWorkflow[Ctx],
        private val eng: WorkflowInstanceEngine[Id],
    ) extends WorkflowInstance[Id, WCState[Ctx]]
        with EventIntrospection[WCEvent[Ctx]] {

      private var currentWorkflow: ActiveWorkflow[Ctx] = initialWorkflow
      private var eventLog: List[WCEvent[Ctx]]         = events.toList

      /** Recover from events - useful for testing recovery scenarios */
      def recover(newEvents: Seq[WCEvent[Ctx]]): Unit = {
        newEvents.foreach { event =>
          eventLog = eventLog :+ event
          eng.handleEvent(currentWorkflow, event).foreach { newWf =>
            currentWorkflow = newWf
          }
        }
      }

      override def id: WorkflowInstanceId = currentWorkflow.id

      override def queryState(): Id[WCState[Ctx]] = eng.queryState(currentWorkflow)

      override def getProgress: Id[model.WIOExecutionProgress[WCState[Ctx]]] = eng.getProgress(currentWorkflow)

      override def getExpectedSignals: Id[List[SignalDef[?, ?]]] = eng.getExpectedSignals(currentWorkflow)

      override def deliverSignal[Req, Resp](signalDef: SignalDef[Req, Resp], req: Req): Id[Either[WorkflowInstance.UnexpectedSignal, Resp]] = {
        val result = eng.handleSignal(currentWorkflow, signalDef, req)
        if !result.hasEffect then Left(WorkflowInstance.UnexpectedSignal(signalDef))
        else {
          val processed    = result.asInstanceOf[workflows4s.wio.internal.SignalResult.Processed[Id, WCEvent[Ctx], Resp]]
          val eventAndResp = processed.resultIO
          eventLog = eventLog :+ eventAndResp.event
          eng.handleEvent(currentWorkflow, eventAndResp.event).foreach { newWf =>
            currentWorkflow = newWf
          }
          // Handle greedy execution
          eng.onStateChange(initialWorkflow, currentWorkflow).foreach { case WorkflowInstanceEngine.PostExecCommand.WakeUp =>
            wakeup()
          }
          Right(eventAndResp.response)
        }
      }

      override def wakeup(): Id[Unit] = {
        val result = eng.triggerWakeup(currentWorkflow)
        if result.hasEffect then {
          val processed        = result.asInstanceOf[workflows4s.wio.internal.WakeupResult.Processed[Id, WCEvent[Ctx]]]
          val processingResult = processed.result
          processingResult.toRaw match {
            case Right(event) =>
              eventLog = eventLog :+ event
              eng.handleEvent(currentWorkflow, event).foreach { newWf =>
                currentWorkflow = newWf
              }
              // Handle greedy execution
              eng.onStateChange(initialWorkflow, currentWorkflow).foreach { case WorkflowInstanceEngine.PostExecCommand.WakeUp =>
                wakeup()
              }
            case Left(_)      => () // Retry scheduled, nothing to do
          }
        }
      }

      override def getEvents: Seq[WCEvent[Ctx]] = eventLog
    }
  }

  // Alias for backwards compatibility
  type InMemory[Ctx <: WorkflowContext] = InMemorySync[Ctx]
  def InMemory[Ctx <: WorkflowContext](): InMemorySync[Ctx] = InMemorySync[Ctx]()

}
