package workflows4s.runtime

import cats.Id
import com.typesafe.scalalogging.StrictLogging
import workflows4s.effect.Effect
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine
import workflows4s.wio.*

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.util.chaining.scalaUtilChainingOps

class InMemorySyncWorkflowInstance[Ctx <: WorkflowContext](
    val id: WorkflowInstanceId,
    initialState: ActiveWorkflow[Ctx],
    protected val engine: WorkflowInstanceEngine[Id],
) extends WorkflowInstanceBase[Id, Id, Ctx]
    with StrictLogging {

  override protected given E: Effect[Id]       = Effect.idEffect
  override protected given EngineE: Effect[Id] = Effect.idEffect

  private var wf: ActiveWorkflow[Ctx]              = initialState
  private val events: mutable.Buffer[WCEvent[Ctx]] = ListBuffer[WCEvent[Ctx]]()
  def getEvents: Seq[WCEvent[Ctx]]                 = events.toList

  def recover(events: Seq[WCEvent[Ctx]]): Unit = super.recover(wf, events).pipe(updateState(_, events))

  override protected def liftEngineEffect[A](fa: Id[A]): Id[A] = fa

  override protected def getWorkflow: workflows4s.wio.ActiveWorkflow[Ctx] = wf

  private val lock = new Object

  override protected def persistEvent(event: WCEvent[Ctx]): Id[Unit] = events += event

  override protected def updateState(newState: ActiveWorkflow[Ctx]): Id[Unit] = wf = newState

  override protected def lockState[T](update: ActiveWorkflow[Ctx] => Id[T]): Id[T] = lock.synchronized { update(wf) }

  private def updateState(workflow: workflows4s.wio.ActiveWorkflow[Ctx], _events: Seq[WCEvent[Ctx]]): Unit = {
    events ++= _events
    wf = workflow
  }

}
