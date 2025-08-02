package workflows4s.runtime.instanceengine

import cats.effect.IO
import workflows4s.runtime.registry.WorkflowRegistry
import workflows4s.runtime.wakeup.KnockerUpper

import java.time.Clock

object WorkflowInstanceEngineBuilder {

  def withJavaTime(clock: Clock = Clock.systemUTC()) = Step1(new BasicJavaTimeEngine(clock))
  def withCatsTime(clock: cats.effect.Clock[IO])     = Step1(new BasicCatsTimeEngine(clock))

  class Step1(val get: WorkflowInstanceEngine) {

    def withWakeUps(knockerUpper: KnockerUpper.Agent) = Step2(new WakingWorkflowInstanceEngine(get, knockerUpper))
    def withoutWakeUps                                = Step2(get)

    class Step2(val get: WorkflowInstanceEngine) {

      def withRegistering(registry: WorkflowRegistry.Agent) = Step3(new RegisteringWorkflowInstanceEngine(get, registry))
      def withoutRegistering                                = Step3(get)

      class Step3(val get: WorkflowInstanceEngine) {

        def withGreedyEvaluation     = Step4(GreedyWorkflowInstanceEngine(get))
        def withSingleStepEvaluation = Step4(get)

        class Step4(val get: WorkflowInstanceEngine) {

          def withLogging    = Step5(new LoggingWorkflowInstanceEngine(get))
          def withoutLogging = Step5(get)

          class Step5(val get: WorkflowInstanceEngine) {}

        }

      }

    }

  }

}
