package workflows4s.runtime.instanceengine

import cats.MonadThrow
import workflows4s.wio.{WCEffectLift, WeakSync, WorkflowContext}

import java.time.{Clock, Instant}

class BasicJavaTimeEngine[F[_]: {MonadThrow, WeakSync}, Ctx <: WorkflowContext](
    clock: Clock,
    override val liftWCEffect: WCEffectLift[Ctx, F],
) extends BasicEngine[F, Ctx] {

  override protected def now: F[Instant] = WeakSync[F].delay(clock.instant())

}
