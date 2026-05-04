package workflows4s.runtime.instanceengine

import cats.MonadThrow
import workflows4s.wio.{WCEffectLift, WorkflowContext}
import workflows4s.wio.internal.WeakSync

import java.time.{Clock, Instant}

class BasicJavaTimeEngine[F[_]: MonadThrow, Ctx <: WorkflowContext](
    clock: Clock,
    override val liftWCEffect: WCEffectLift[Ctx, F],
) extends BasicEngine[F, Ctx] {

  override protected def now: F[Instant] = WeakSync.delay[F](clock.instant())

}
