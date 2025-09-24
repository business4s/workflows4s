package workflows4s.example.docs

import workflows4s.runtime.instanceengine.WorkflowInstanceEngine
import workflows4s.runtime.registry.WorkflowRegistry
import workflows4s.runtime.wakeup.KnockerUpper

object EngineExample {

  // doc_start
  val knockerUpper: KnockerUpper.Agent = ???
  val registry: WorkflowRegistry.Agent = ???

  val engine: WorkflowInstanceEngine = WorkflowInstanceEngine.builder
    .withJavaTime()
    .withWakeUps(knockerUpper)
    .withRegistering(registry)
    .withGreedyEvaluation
    .withLogging
    .get
  // doc_end

}
