package workflows4s.runtime.instanceengine

import cats.MonadThrow
import workflows4s.runtime.registry.WorkflowRegistry
import workflows4s.runtime.wakeup.KnockerUpper
import workflows4s.wio.{LiftWorkflowEffect, MonadThrowContainer, WCEffect, WeakSync, WorkflowContext}

import java.time.Clock

object WorkflowInstanceEngineBuilder {

  def withJavaTime[F[_]: {MonadThrow, WeakSync}](clock: Clock = Clock.systemUTC()) = Step1[F](clock)

  class Step1[F[_]: {MonadThrow, WeakSync}](clock: Clock) {

    def withWakeUps(knockerUpper: KnockerUpper.Agent[F]) = Step2(Some(knockerUpper))
    def withoutWakeUps                                   = Step2(None)

    class Step2(knockerUpper: Option[KnockerUpper.Agent[F]]) {

      def withRegistering(registry: WorkflowRegistry.Agent[F]) = Step3(Some(registry))
      def withoutRegistering                                   = Step3(None)

      class Step3(registry: Option[WorkflowRegistry.Agent[F]]) {

        def withGreedyEvaluation     = Step4(greedy = true)
        def withSingleStepEvaluation = Step4(greedy = false)

        class Step4(greedy: Boolean) {

          def withLogging    = Step5(logging = true)
          def withoutLogging = Step5(logging = false)

          class Step5(logging: Boolean) {

            def get[Ctx <: WorkflowContext](
                liftWCEffect: [A] => WCEffect[Ctx][A] => F[A],
                wcEffectMonadThrow: MonadThrow[WCEffect[Ctx]],
            ): WorkflowInstanceEngine[F, Ctx] = {
              var engine: WorkflowInstanceEngine[F, Ctx] = new BasicJavaTimeEngine[F, Ctx](clock, wcEffectMonadThrow, liftWCEffect)
              knockerUpper.foreach(ku => engine = new WakingWorkflowInstanceEngine(engine, ku))
              registry.foreach(r => engine = new RegisteringWorkflowInstanceEngine(engine, r))
              if greedy then engine = new GreedyWorkflowInstanceEngine(engine)
              if logging then engine = new LoggingWorkflowInstanceEngine(engine)
              engine
            }

            def get[Ctx <: WorkflowContext](using wcEffectMonadThrow: MonadThrowContainer[Ctx], ev: LiftWorkflowEffect[Ctx, F]): WorkflowInstanceEngine[F, Ctx] = {
              get(ev.asPoly, wcEffectMonadThrow.instance)
            }

          }

        }

      }

    }

  }

}
