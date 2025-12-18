package workflows4s.runtime.instanceengine

import workflows4s.runtime.registry.WorkflowRegistry
import workflows4s.runtime.wakeup.KnockerUpper
import java.time.Clock

trait WorkflowInstanceEngineBuilder[F[_]](using E: Effect[F]) {

  // Methods to kick off the builder
  def withJavaTime(clock: Clock = Clock.systemUTC()) = Step1(new BasicJavaTimeEngine[F](clock))

  // We use F throughout the internal steps
  class Step1(val get: WorkflowInstanceEngine[F]) {

    def withWakeUps(knockerUpper: KnockerUpper.Agent[F]) = Step2(new WakingWorkflowInstanceEngine[F](get, knockerUpper))
    def withoutWakeUps                                   = Step2(get)

    class Step2(val get: WorkflowInstanceEngine[F]) {

      def withRegistering(registry: WorkflowRegistry.Agent[F]) = Step3(new RegisteringWorkflowInstanceEngine[F](get, registry))
      def withoutRegistering                                   = Step3(get)

      class Step3(val get: WorkflowInstanceEngine[F]) {

        def withGreedyEvaluation     = Step4(GreedyWorkflowInstanceEngine[F](get))
        def withSingleStepEvaluation = Step4(get)

        class Step4(val get: WorkflowInstanceEngine[F]) {

          def withLogging    = Step5(new LoggingWorkflowInstanceEngine[F](get))
          def withoutLogging = Step5(get)

          // Final step returns the engine wrapped in F
          class Step5(val get: WorkflowInstanceEngine[F])
        }
      }
    }
  }
}
