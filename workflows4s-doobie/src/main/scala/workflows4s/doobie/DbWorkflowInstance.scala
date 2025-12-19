package workflows4s.doobie

import cats.arrow.FunctionK
import cats.data.Kleisli
import cats.effect.{IO, LiftIO}
import cats.effect.syntax.monadCancel.*
import cats.syntax.all.*
import cats.{Monad, ~>}
import com.typesafe.scalalogging.StrictLogging
import doobie.ConnectionIO
import doobie.implicits.*
import workflows4s.runtime.instanceengine.{Effect, WorkflowInstanceEngine}
import workflows4s.runtime.{WorkflowInstanceBase, WorkflowInstanceId}
import workflows4s.wio.*

private[doobie] type Result[T] = Kleisli[ConnectionIO, LiftIO[ConnectionIO], T]

private[doobie] given resultEffect: Effect[Result] = new Effect[Result] {
  private val M: Monad[Result] = summon

  type Mutex = java.util.concurrent.Semaphore

  def createMutex: Mutex = new java.util.concurrent.Semaphore(1)

  def withLock[A](m: Mutex)(fa: Result[A]): Result[A] = {
    Kleisli { liftIO =>
      doobie.FC.delay(m.acquire()) *> fa.run(liftIO).guarantee(doobie.FC.delay(m.release()))
    }
  }

  def pure[A](a: A): Result[A]                                                   = M.pure(a)
  def flatMap[A, B](fa: Result[A])(f: A => Result[B]): Result[B]                 = M.flatMap(fa)(f)
  def map[A, B](fa: Result[A])(f: A => B): Result[B]                             = M.map(fa)(f)
  def raiseError[A](e: Throwable): Result[A]                                     = Kleisli(_ => doobie.FC.raiseError(e))
  def handleErrorWith[A](fa: => Result[A])(f: Throwable => Result[A]): Result[A] =
    Kleisli(liftIO => fa.run(liftIO).handleErrorWith(e => f(e).run(liftIO)))
  def sleep(duration: scala.concurrent.duration.FiniteDuration): Result[Unit]    =
    Kleisli(liftIO => liftIO.liftIO(IO.sleep(duration)))
  def realTimeInstant: Result[java.time.Instant]                                 =
    Kleisli(liftIO => liftIO.liftIO(IO.realTimeInstant))
  def delay[A](a: => A): Result[A]                                               =
    Kleisli(_ => doobie.FC.delay(a))
}

class DbWorkflowInstance[Ctx <: WorkflowContext](
    val id: WorkflowInstanceId,
    baseWorkflow: ActiveWorkflow[Result, Ctx],
    storage: WorkflowStorage[WCEvent[Ctx]],
    protected val engine: WorkflowInstanceEngine[Result],
) extends WorkflowInstanceBase[Result, Ctx]
    with StrictLogging {

  private val connIOToResult: ConnectionIO ~> Result = new FunctionK {
    override def apply[A](fa: ConnectionIO[A]): Result[A] = Kleisli(_ => fa)
  }

  override protected def getWorkflow: Result[ActiveWorkflow[Result, Ctx]] = {
    Kleisli(connLifIo =>
      storage
        .getEvents(id)
        .evalFold(baseWorkflow)((state, event) => engine.processEvent(state, event).run(connLifIo))
        .compile
        .lastOrError,
    )
  }

  override protected def persistEvent(event: WCEvent[Ctx]): Result[Unit] = Kleisli(_ => storage.saveEvent(id, event))

  override protected def updateState(newState: ActiveWorkflow[Result, Ctx]): Result[Unit] = resultEffect.unit

  override protected def lockState[T](update: ActiveWorkflow[Result, Ctx] => Result[T]): Result[T] =
    storage.lockWorkflow(id).mapK(connIOToResult).use(_ => getWorkflow.flatMap(update))
}
