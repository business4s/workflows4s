package workflows4s.runtime

import cats.{Monad, MonadThrow}
import cats.syntax.all.*
import com.typesafe.scalalogging.StrictLogging
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine
import workflows4s.wio.*
import workflows4s.wio.internal.WeakSync

import scala.collection.mutable
import scala.collection.mutable.ListBuffer

class InMemorySynchronizedWorkflowInstance[F[_]: MonadThrow, Ctx <: WorkflowContext](
    val id: WorkflowInstanceId,
    initialState: ActiveWorkflow[Ctx],
    protected val engine: WorkflowInstanceEngine[F, Ctx],
) extends WorkflowInstanceBase[F, Ctx]
    with StrictLogging {

  private var wf: ActiveWorkflow[Ctx]              = initialState
  private val events: mutable.Buffer[WCEvent[Ctx]] = ListBuffer[WCEvent[Ctx]]()
  def getEvents: Seq[WCEvent[Ctx]]                 = events.toList

  def recover(events: Seq[WCEvent[Ctx]]): F[Unit] =
    lockState { currentWf =>
      super.recover(currentWf, events).flatMap { newState =>
        WeakSync.delay[F] {
          this.events ++= events
          wf = newState
        }
      }
    }

  override protected given fMonad: Monad[F]                  = MonadThrow[F]
  override protected def getWorkflow: F[ActiveWorkflow[Ctx]] = WeakSync.delay[F](wf)

  private val lock = new java.util.concurrent.Semaphore(1)

  override protected def persistEvent(event: WCEvent[Ctx]): F[Unit] = WeakSync.delay[F] { events += event }

  override protected def updateState(newState: ActiveWorkflow[Ctx]): F[Unit] = WeakSync.delay[F] { wf = newState }

  // Intentionally not cancellation-safe: we only require `MonadThrow` (no `MonadCancel`), which
  // rules out `Resource.make`. If `F` is cancellable and the fiber is cancelled mid-update, the
  // lock will not be released. This is a documented tradeoff of the synchronized runtime; use
  // `InMemoryConcurrentRuntime` when cancellation safety is required.
  override protected def lockState[T](update: ActiveWorkflow[Ctx] => F[T]): F[T] =
    for {
      currentWf <- WeakSync.delay[F] { lock.acquire(); wf }
      result    <- MonadThrow[F].attempt(update(currentWf))
      _         <- WeakSync.delay[F](lock.release())
      value     <- MonadThrow[F].fromEither(result)
    } yield value

}
