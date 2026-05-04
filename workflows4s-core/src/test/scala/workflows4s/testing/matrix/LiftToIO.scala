package workflows4s.testing.matrix

import cats.effect.IO
import workflows4s.wio.LiftWorkflowEffect

import scala.util.Try

/** LiftWorkflowEffect instances that bridge non-IO workflow contexts to IO, for use in IO-based runtimes (doobie, pekko). */
object LiftToIO {

  given liftTry: LiftWorkflowEffect[TestCtxTry.Ctx, IO] = LiftWorkflowEffect.through[TestCtxTry.Ctx, Try]([A] => (fa: Try[A]) => IO.fromTry(fa))

  given liftEither: LiftWorkflowEffect[TestCtxEither.Ctx, IO] =
    LiftWorkflowEffect.through[TestCtxEither.Ctx, Either[Throwable, *]]([A] => (fa: Either[Throwable, A]) => IO.fromEither(fa))

  given liftThunk: LiftWorkflowEffect[TestCtxThunk.Ctx, IO] =
    LiftWorkflowEffect.through[TestCtxThunk.Ctx, Function0]([A] => (fa: () => A) => IO(fa()))

}
