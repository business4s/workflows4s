package workflows4s.runtime

import cats.{Monad, MonadThrow}
import cats.syntax.all.*
import com.typesafe.scalalogging.StrictLogging
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine
import workflows4s.wio.*

import scala.collection.mutable
import scala.collection.mutable.ListBuffer

class InMemorySynchronizedWorkflowInstance[F[_]: {MonadThrow, WeakSync}, Ctx <: WorkflowContext](
    val id: WorkflowInstanceId,
    initialState: ActiveWorkflow[F, Ctx],
    protected val engine: WorkflowInstanceEngine[F],
) extends WorkflowInstanceBase[F, F, Ctx]
    with StrictLogging {

  private var wf: ActiveWorkflow[F, Ctx]           = initialState
  private val events: mutable.Buffer[WCEvent[Ctx]] = ListBuffer[WCEvent[Ctx]]()
  def getEvents: Seq[WCEvent[Ctx]]                 = events.toList

  def recover(events: Seq[WCEvent[Ctx]]): F[Unit] =
    super.recover(wf, events).flatMap { newState =>
      WeakSync[F].delay {
        this.events ++= events
        wf = newState
      }
    }

  override protected def liftG: [A] => F[A] => F[A]             = [A] => (fa: F[A]) => fa
  override protected given fMonad: Monad[F]                     = MonadThrow[F]
  override protected def getWorkflow: F[ActiveWorkflow[F, Ctx]] = WeakSync[F].delay(wf)

  private val lock = new java.util.concurrent.Semaphore(1)

  override protected def persistEvent(event: WCEvent[Ctx]): F[Unit] = WeakSync[F].delay { events += event }

  override protected def updateState(newState: ActiveWorkflow[F, Ctx]): F[Unit] = WeakSync[F].delay { wf = newState }

  override protected def lockState[T](update: ActiveWorkflow[F, Ctx] => F[T]): F[T] =
    for {
      currentWf <- WeakSync[F].delay { lock.acquire(); wf }
      result    <- MonadThrow[F].attempt(update(currentWf))
      _         <- WeakSync[F].delay(lock.release())
      value     <- MonadThrow[F].fromEither(result)
    } yield value

}
