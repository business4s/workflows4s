package workflows4s.example.docs

import cats.effect.IO
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine
import workflows4s.runtime.registry.WorkflowRegistry
import workflows4s.runtime.wakeup.KnockerUpper
import workflows4s.wio.WorkflowContext
import workflows4s.wio.cats.effect.WeakSyncInstances.given

object EngineExample {

  object MyWorkflowCtx extends WorkflowContext {
    type Effect[T] = IO[T]
    sealed trait State
    sealed trait Event
  }

  // doc_start
  val knockerUpper: KnockerUpper.Agent[IO] = ???
  val registry: WorkflowRegistry.Agent[IO] = ???

  val engine: WorkflowInstanceEngine[IO, MyWorkflowCtx.Ctx] = WorkflowInstanceEngine.builder
    .withJavaTime[IO]()
    .withWakeUps(knockerUpper)
    .withRegistering(registry)
    .withGreedyEvaluation
    .withLogging
    .get
  // doc_end

}
