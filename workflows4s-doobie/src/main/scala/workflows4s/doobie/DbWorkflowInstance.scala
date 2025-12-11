package workflows4s.doobie

import cats.arrow.FunctionK
import cats.data.Kleisli
import cats.effect.{IO, LiftIO, Sync}
import cats.~>
import com.typesafe.scalalogging.StrictLogging
import doobie.ConnectionIO
import doobie.implicits.*
import workflows4s.effect.Effect
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine
import workflows4s.runtime.{WorkflowInstanceBase, WorkflowInstanceId}
import workflows4s.wio.*

import java.time.Instant
import scala.concurrent.duration.FiniteDuration
import scala.util.chaining.scalaUtilChainingOps

private type Result[T] = Kleisli[ConnectionIO, LiftIO[ConnectionIO], T]

class DbWorkflowInstance[Ctx <: WorkflowContext](
    val id: WorkflowInstanceId,
    baseWorkflow: ActiveWorkflow[Ctx],
    storage: WorkflowStorage[WCEvent[Ctx]],
    protected val engine: WorkflowInstanceEngine[IO],
) extends WorkflowInstanceBase[Result, IO, Ctx]
    with StrictLogging {

  // Effect instance for Result (Kleisli-based type)
  override protected given E: Effect[Result] = new Effect[Result] {
    def pure[A](a: A): Result[A]                                                = Kleisli.pure(a)
    def flatMap[A, B](fa: Result[A])(f: A => Result[B]): Result[B]              = fa.flatMap(f)
    def map[A, B](fa: Result[A])(f: A => B): Result[B]                          = fa.map(f)
    def raiseError[A](e: Throwable): Result[A]                                  = Kleisli(_ => Sync[ConnectionIO].raiseError(e))
    def handleErrorWith[A](fa: Result[A])(f: Throwable => Result[A]): Result[A] =
      Kleisli(env => Sync[ConnectionIO].handleErrorWith(fa.run(env))(e => f(e).run(env)))
    def sleep(duration: FiniteDuration): Result[Unit]                           = Kleisli(liftIO => liftIO.liftIO(IO.sleep(duration)))
    def realTimeInstant: Result[Instant]                                        = Kleisli(liftIO => liftIO.liftIO(IO.realTimeInstant))
    def delay[A](a: => A): Result[A]                                            = Kleisli(_ => Sync[ConnectionIO].delay(a))
  }

  override protected given EngineE: Effect[IO] = workflows4s.catseffect.CatsEffect.ioEffect

  private val connIOToResult: ConnectionIO ~> Result = new FunctionK {
    override def apply[A](fa: ConnectionIO[A]): Result[A] = Kleisli(_ => fa)
  }

  override protected def liftEngineEffect[A](fa: IO[A]): Result[A] = Kleisli(liftIO => liftIO.liftIO(fa))

  override protected def getWorkflow: Result[ActiveWorkflow[Ctx]] = {
    Kleisli(connLifIo =>
      storage
        .getEvents(id)
        .evalFold(baseWorkflow)((state, event) => engine.processEvent(state, event).to[IO].pipe(connLifIo.liftIO))
        .compile
        .lastOrError,
    )
  }

  override protected def persistEvent(event: WCEvent[Ctx]): Result[Unit] = Kleisli(_ => storage.saveEvent(id, event))

  override protected def updateState(newState: ActiveWorkflow[Ctx]): Result[Unit] = E.unit

  override protected def lockState[T](update: ActiveWorkflow[Ctx] => Result[T]): Result[T] =
    storage.lockWorkflow(id).mapK(connIOToResult).use(_ => getWorkflow.flatMap(update))
}
