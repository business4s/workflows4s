package workflows4s.runtime.instanceengine

import cats.MonadThrow
import workflows4s.runtime.registry.WorkflowRegistry
import workflows4s.runtime.wakeup.KnockerUpper
import workflows4s.wio.WeakSync

import java.time.Clock

object WorkflowInstanceEngineBuilder {

  def withJavaTime[F[_]: {MonadThrow, WeakSync}](clock: Clock = Clock.systemUTC()) = Step1[F](new BasicJavaTimeEngine[F](clock))

  class Step1[F[_]: {MonadThrow, WeakSync}](val get: WorkflowInstanceEngine[F]) {

    def withWakeUps(knockerUpper: KnockerUpper.Agent[F]) = Step2(new WakingWorkflowInstanceEngine(get, knockerUpper))
    def withoutWakeUps                                   = Step2(get)

    class Step2(val get: WorkflowInstanceEngine[F]) {

      def withRegistering(registry: WorkflowRegistry.Agent[F]) = Step3(new RegisteringWorkflowInstanceEngine(get, registry))
      def withoutRegistering                                   = Step3(get)

      class Step3(val get: WorkflowInstanceEngine[F]) {

        def withGreedyEvaluation     = Step4(GreedyWorkflowInstanceEngine(get))
        def withSingleStepEvaluation = Step4(get)

        class Step4(val get: WorkflowInstanceEngine[F]) {

          def withLogging    = Step5(new LoggingWorkflowInstanceEngine(get))
          def withoutLogging = Step5(get)

          class Step5(val get: WorkflowInstanceEngine[F]) {}

        }

      }

    }

  }

}
