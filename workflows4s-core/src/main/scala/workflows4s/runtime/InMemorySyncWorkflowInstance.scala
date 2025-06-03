package workflows4s.runtime

import cats.effect.unsafe.IORuntime
import cats.effect.{IO, LiftIO}
import cats.{Id, Monad}
import com.typesafe.scalalogging.StrictLogging
import workflows4s.runtime.registry.WorkflowRegistry
import workflows4s.runtime.wakeup.KnockerUpper
import workflows4s.wio.*

import java.time.Clock
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.util.chaining.scalaUtilChainingOps

// TODO this could be made thread-safe using java primitives
class InMemorySyncWorkflowInstance[Ctx <: WorkflowContext](
    initialState: ActiveWorkflow[Ctx],
    protected val clock: Clock,
    protected val knockerUpper: KnockerUpper.Agent.Curried,
    protected val registry: WorkflowRegistry.Agent.Curried,
)(implicit
    IORuntime: IORuntime,
) extends WorkflowInstanceBase[Id, Ctx]
    with StrictLogging {

  private var wf: ActiveWorkflow[Ctx]              = initialState
  private val events: mutable.Buffer[WCEvent[Ctx]] = ListBuffer[WCEvent[Ctx]]()
  def getEvents: Seq[WCEvent[Ctx]]                 = events.toList

  def recover(events: Seq[WCEvent[Ctx]]): Unit = super.recover(wf, events).pipe(updateState(_, events))

  override protected def liftIO: cats.effect.LiftIO[Id]                   = new LiftIO[Id] {
    override def liftIO[A](ioa: IO[A]): Id[A] = ioa.unsafeRunSync()
  }
  override protected def fMonad: Monad[Id]                                = cats.Invariant.catsInstancesForId
  override protected def getWorkflow: workflows4s.wio.ActiveWorkflow[Ctx] = wf

  override protected def lockAndUpdateState[T](update: ActiveWorkflow[Ctx] => LockOutcome[T]): StateUpdate[T] = {
    val oldState = wf
    update(oldState) match {
      case LockOutcome.NewEvent(event, result) =>
        val newState = processLiveEvent(event, oldState, clock.instant())
        updateState(newState, Seq(event))
        StateUpdate.Updated(oldState, newState, result)
      case LockOutcome.NoOp(result)            => StateUpdate.NoOp(oldState, result)
    }
  }

  private def updateState(workflow: workflows4s.wio.ActiveWorkflow[Ctx], _events: Seq[WCEvent[Ctx]]): Unit = {
    events ++= _events
    wf = workflow
  }

}
