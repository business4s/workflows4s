package workflows4s.testing

import cats.Id
import com.typesafe.scalalogging.StrictLogging
import workflows4s.runtime.*
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine
import workflows4s.runtime.registry.InMemoryWorkflowRegistry
import workflows4s.wio.*

// Adapt various runtimes to a single interface for tests
trait TestRuntimeAdapter[F[_], Ctx <: WorkflowContext] extends StrictLogging {

  protected def knockerUpper: RecordingKnockerUpper[F]
  val clock: TestClock
  val registry: InMemoryWorkflowRegistry[F]

  def engine: WorkflowInstanceEngine[F, Ctx]

  def runSync[A](fa: F[A]): A

  type Actor <: WorkflowInstance[Id, WCState[Ctx]]

  def runWorkflow(
      workflow: WIO[Any, Nothing, WCState[Ctx], Ctx],
      state: WCState[Ctx],
  ): Actor

  def recover(first: Actor): Actor

  final def executeDueWakup(actor: Actor): Unit = {
    val wakeup = knockerUpper.lastRegisteredWakeup(actor.id)
    logger.debug(s"Executing due wakeup for actor ${actor.id}. Last registered wakeup: ${wakeup}")
    if wakeup.exists(_.isBefore(clock.instant()))
    then actor.wakeup()
  }

}

object TestRuntimeAdapter {

  trait EventIntrospection[Event] {
    def getEvents: Seq[Event]
  }

}
