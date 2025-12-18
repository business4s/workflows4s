package workflows4s.doobie.testing

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.typesafe.scalalogging.StrictLogging
import workflows4s.doobie.{Result, resultEffect}
import workflows4s.runtime.*
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine
import workflows4s.runtime.registry.InMemoryWorkflowRegistry
import workflows4s.testing.{RecordingKnockerUpper, TestClock}
import workflows4s.wio.*

/** Test adapter for doobie runtimes that use Result effect type internally. The adapter exposes IO-based operations by wrapping the Result-based
  * workflow instance.
  */
trait ResultTestRuntimeAdapter[Ctx <: WorkflowContext] extends StrictLogging {

  protected val knockerUpper             = RecordingKnockerUpper()
  val clock: TestClock                   = TestClock()
  val registry: InMemoryWorkflowRegistry = InMemoryWorkflowRegistry(clock).unsafeRunSync()

  // Engine for Result effect type (internal doobie type)
  val resultEngine: WorkflowInstanceEngine[Result] = WorkflowInstanceEngine.basic()

  type Actor <: WorkflowInstance[IO, WCState[Ctx]]

  def runWorkflow(
      workflow: WIO.Initial[Result, Ctx],
      state: WCState[Ctx],
  ): Actor

  def recover(first: Actor): Actor

  final def executeDueWakeup(actor: Actor): Unit = {
    val wakeup = knockerUpper.lastRegisteredWakeup(actor.id)
    logger.debug(s"Executing due wakeup for actor ${actor.id}. Last registered wakeup: ${wakeup}")
    if wakeup.exists(_.isBefore(clock.instant()))
    then actor.wakeup().unsafeRunSync()
  }

}
