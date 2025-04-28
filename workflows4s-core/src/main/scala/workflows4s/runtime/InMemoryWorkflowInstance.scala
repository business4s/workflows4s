package workflows4s.runtime

import cats.Monad
import cats.effect.{IO, LiftIO, Ref}
import workflows4s.runtime.wakeup.KnockerUpper
import workflows4s.runtime.wakeup.KnockerUpper.Agent.Curried
import workflows4s.wio.*

import java.time.Clock

// WARNING: current implementation is not safe in a concurrent scenario.
// See https://github.com/business4s/workflows4s/issues/60 for details.
class InMemoryWorkflowInstance[Ctx <: WorkflowContext](
    stateRef: Ref[IO, ActiveWorkflow[Ctx]],
    eventsRef: Ref[IO, Vector[WCEvent[Ctx]]],
    protected val clock: Clock,
    protected val knockerUpper: KnockerUpper.Agent.Curried,
) extends WorkflowInstanceBase[IO, Ctx] {

  def getEvents: IO[Vector[WCEvent[Ctx]]]          = eventsRef.get
  override def recover(events: Seq[WCEvent[Ctx]]): IO[Unit] = super.recover(events)

  override protected def fMonad: Monad[IO]  = summon
  override protected def liftIO: LiftIO[IO] = summon

  override protected def getWorkflow: IO[ActiveWorkflow[Ctx]] = stateRef.get

  override protected def updateState(event: Option[WCEvent[Ctx]], workflow: ActiveWorkflow[Ctx]): IO[Unit] = {
    for {
      _ <- stateRef.set(workflow)
      _ <- event.map(event => eventsRef.update(_ :+ event)).getOrElse(IO.unit)
    } yield ()
  }
}
