package workflows4s.runtime.instanceengine

import cats.effect.IO
import workflows4s.catseffect.CatsEffect.given
import workflows4s.effect.Effect
import workflows4s.runtime.registry.WorkflowRegistry
import workflows4s.runtime.wakeup.KnockerUpper

import java.time.Clock

object WorkflowInstanceEngineBuilder {

  def withJavaTime[F[_]](clock: Clock = Clock.systemUTC())(using E: Effect[F]) = Step1(new BasicJavaTimeEngine[F](clock))
  def withCatsTime(clock: cats.effect.Clock[IO])                               = Step1IO(new BasicCatsTimeEngine(clock))

  class Step1[F[_]](val get: WorkflowInstanceEngine[F])(using E: Effect[F]) {

    def withWakeUps(knockerUpper: KnockerUpper.Agent[F]) = Step2(new WakingWorkflowInstanceEngine[F](get, knockerUpper))
    def withoutWakeUps                                   = Step2(get)

    class Step2(val get: WorkflowInstanceEngine[F]) {

      def withRegistering(registry: WorkflowRegistry.Agent[F]) = Step3(new RegisteringWorkflowInstanceEngine[F](get, registry))
      def withoutRegistering                                   = Step3(get)

      class Step3(val get: WorkflowInstanceEngine[F]) {

        def withGreedyEvaluation     = Step4(new GreedyWorkflowInstanceEngine[F](get))
        def withSingleStepEvaluation = Step4(get)

        class Step4(val get: WorkflowInstanceEngine[F]) {

          def withLogging    = Step5(new LoggingWorkflowInstanceEngine[F](get))
          def withoutLogging = Step5(get)

          class Step5(val get: WorkflowInstanceEngine[F]) {}

        }

      }

    }

  }

  // Specialized builder for IO to avoid type parameter when using withCatsTime
  class Step1IO(val get: WorkflowInstanceEngine[IO]) {

    def withWakeUps(knockerUpper: KnockerUpper.Agent[IO]) = Step2IO(new WakingWorkflowInstanceEngine[IO](get, knockerUpper))
    def withoutWakeUps                                    = Step2IO(get)

    class Step2IO(val get: WorkflowInstanceEngine[IO]) {

      def withRegistering(registry: WorkflowRegistry.Agent[IO]) = Step3IO(new RegisteringWorkflowInstanceEngine[IO](get, registry))
      def withoutRegistering                                    = Step3IO(get)

      class Step3IO(val get: WorkflowInstanceEngine[IO]) {

        def withGreedyEvaluation     = Step4IO(new GreedyWorkflowInstanceEngine[IO](get))
        def withSingleStepEvaluation = Step4IO(get)

        class Step4IO(val get: WorkflowInstanceEngine[IO]) {

          def withLogging    = Step5IO(new LoggingWorkflowInstanceEngine[IO](get))
          def withoutLogging = Step5IO(get)

          class Step5IO(val get: WorkflowInstanceEngine[IO]) {}

        }

      }

    }

  }

}
