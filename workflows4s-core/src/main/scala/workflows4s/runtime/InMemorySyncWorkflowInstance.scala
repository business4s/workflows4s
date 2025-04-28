package workflows4s.runtime

import cats.effect.unsafe.IORuntime
import cats.effect.{IO, LiftIO}
import cats.{Id, Monad}
import com.typesafe.scalalogging.StrictLogging
import workflows4s.runtime.wakeup.KnockerUpper
import workflows4s.wio.*

import java.time.Clock
import scala.collection.mutable
import scala.collection.mutable.ListBuffer

class InMemorySyncWorkflowInstance[Ctx <: WorkflowContext](
    initialState: ActiveWorkflow[Ctx],
    protected val clock: Clock,
    protected val knockerUpper: KnockerUpper.Agent.Curried,
)(implicit
    IORuntime: IORuntime,
) extends WorkflowInstanceBase[Id, Ctx]
    with StrictLogging {

  private var wf: ActiveWorkflow[Ctx]              = initialState
  private val events: mutable.Buffer[WCEvent[Ctx]] = ListBuffer[WCEvent[Ctx]]()
  def getEvents: Seq[WCEvent[Ctx]]                 = events.toList

  override def recover(events: Seq[WCEvent[Ctx]]): Unit = super.recover(events)

  override protected def liftIO: cats.effect.LiftIO[Id] = new LiftIO[Id] {
    override def liftIO[A](ioa: IO[A]): Id[A] = ioa.unsafeRunSync()
  }
  override protected def fMonad: Monad[Id] = cats.Invariant.catsInstancesForId
  override protected def getWorkflow: workflows4s.wio.ActiveWorkflow[Ctx] = wf

  override protected def updateState(event: Option[WCEvent[Ctx]], workflow: workflows4s.wio.ActiveWorkflow[Ctx]): Unit = {
    event.foreach(events += _)
    wf = workflow
  }

}
