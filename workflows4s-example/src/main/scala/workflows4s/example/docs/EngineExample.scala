package workflows4s.example.docs

import cats.effect.IO
import workflows4s.cats.CatsEffect.given
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine
import workflows4s.runtime.registry.WorkflowRegistry
import workflows4s.runtime.wakeup.KnockerUpper

object EngineExample {

  // doc_start
  val knockerUpper: KnockerUpper.Agent[IO] = ???
  val registry: WorkflowRegistry.Agent[IO] = ???

  val engine: WorkflowInstanceEngine[IO] = WorkflowInstanceEngine
    .builder[IO]
    .withJavaTime()
    .withWakeUps(knockerUpper)
    .withRegistering(registry)
    .withGreedyEvaluation
    .withLogging
    .get
  // doc_end

}
