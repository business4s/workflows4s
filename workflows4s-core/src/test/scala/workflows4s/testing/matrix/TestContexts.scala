package workflows4s.testing.matrix

import cats.Applicative
import cats.effect.IO
import workflows4s.wio.{StepId, TestState, WorkflowContext}
import zio.Task
import zio.interop.catz.*

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

import EffectInstances.given

sealed trait TestEvent extends Serializable
object TestEvent {
  case class RunIODone(stepId: StepId)  extends TestEvent
  case class SigDone(req: Int)          extends TestEvent
  case class TimerStarted(at: Instant)  extends TestEvent
  case class TimerReleased(at: Instant) extends TestEvent
}

trait TestContextBase extends WorkflowContext {
  type Event = TestEvent
  type State = TestState
  given effectApplicative: Applicative[Effect]
}

object TestCtxIO extends TestContextBase {
  type Effect[T] = IO[T]
  given effectApplicative: Applicative[IO] = summon
}

object TestCtxTry extends TestContextBase {
  type Effect[T] = Try[T]
  given effectApplicative: Applicative[Try] = summon
}

object TestCtxEither extends TestContextBase {
  type Effect[T] = Either[Throwable, T]
  given effectApplicative: Applicative[[A] =>> Either[Throwable, A]] = summon
}

object TestCtxThunk extends TestContextBase {
  type Effect[T] = Function0[T]
  given effectApplicative: Applicative[Function0] = summon
}

object TestCtxZIO extends TestContextBase {
  type Effect[T] = Task[T]
  given effectApplicative: Applicative[Task] = summon
}

object TestCtxFuture extends TestContextBase {
  private given ExecutionContext = ExecutionContext.global
  type Effect[T] = Future[T]
  given effectApplicative: Applicative[Future] = cats.instances.future.catsStdInstancesForFuture
}
