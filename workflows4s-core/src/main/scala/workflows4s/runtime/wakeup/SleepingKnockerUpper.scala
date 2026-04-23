package workflows4s.runtime.wakeup

import java.time.{Duration, Instant}
import scala.jdk.DurationConverters.JavaDurationOps
import cats.effect.kernel.Outcome
import cats.effect.std.AtomicCell
import cats.effect.{Deferred, FiberIO, IO, Resource}
import cats.implicits.{catsSyntaxApplicativeId, catsSyntaxOptionId, catsSyntaxParallelTraverse1, toTraverseOps}
import com.typesafe.scalalogging.StrictLogging
import workflows4s.runtime.WorkflowInstanceId
import workflows4s.runtime.wakeup.SleepingKnockerUpper.Entry

/** Simple implementation for KnockerUpper that relies on IO.sleep.
  */
class SleepingKnockerUpper(
    state: AtomicCell[IO, Map[WorkflowInstanceId, Entry]],
    wakeupLogicRef: AtomicCell[IO, Option[WorkflowInstanceId => IO[Unit]]],
) extends KnockerUpper.Agent
    with KnockerUpper.Process[IO, IO[Unit]]
    with StrictLogging {

  override def updateWakeup(id: WorkflowInstanceId, at: Option[Instant]): IO[Unit] = {
    at match {
      case Some(wakeupTime) =>
        for {
          wakeupOpt    <- wakeupLogicRef.get
          wakeup       <- IO.fromOption(wakeupOpt)(new Exception("No wakeup logic registered. Please call start before calling updateWakeup."))
          cancelSignal <- Deferred[IO, Unit]
          fiber        <- sleepAndWakeup(id, wakeupTime, wakeup, cancelSignal.get).start
          newEntry      = Entry(wakeupTime, cancelSignal.complete(()).void, fiber)
          prevState    <- state.getAndUpdate(_.updated(id, newEntry))
          _            <- prevState.get(id).traverse(_.cancel)
        } yield ()
      case None             =>
        for {
          prevState <- state.getAndUpdate(_.removed(id))
          _         <- prevState.get(id).traverse(_.cancel)
        } yield ()
    }
  }

  private def sleepAndWakeup(
      id: WorkflowInstanceId,
      at: Instant,
      wakeup: WorkflowInstanceId => IO[Unit],
      awaitCancel: IO[Unit],
  ) = {
    (for {
      now     <- IO(Instant.now())
      duration = Duration.between(now, at).toScala
      _       <- IO(logger.debug(s"Sleeping for $duration before waking up $id"))
      outcome <- IO.race(IO.sleep(duration), awaitCancel)
      _       <- outcome match {
                   case Left(_)  => IO(logger.debug(s"Waking up $id")) *> wakeup(id)
                   case Right(_) => IO(logger.debug(s"Sleep for $id cancelled"))
                 }
    } yield ())
      .guaranteeCase({
        case Outcome.Succeeded(_) => removeSpecific(id, at)
        case Outcome.Errored(e)   => IO(logger.debug(s"Failed to wake up $id", e)) *> removeSpecific(id, at)
        case Outcome.Canceled()   => IO(logger.debug(s"Wakeup fiber for $id terminated")) *> removeSpecific(id, at)
      })
  }

  private def removeSpecific(id: WorkflowInstanceId, at: Instant): IO[Unit] = {
    state.update(st => {
      st.get(id) match {
        case Some(e) if e.at == at => st.removed(id)
        case _                     => st
      }
    })
  }

  override def initialize(wakeUp: WorkflowInstanceId => IO[Unit]): IO[Unit] = {
    wakeupLogicRef.evalUpdate({
      case Some(_) => IO.raiseError(new Exception("Start can be called only once"))
      case None    => wakeUp.some.pure[IO]
    })
  }

}

object SleepingKnockerUpper {

  private[SleepingKnockerUpper] case class Entry(at: Instant, cancel: IO[Unit], fiber: FiberIO[Unit])

  def create(): Resource[IO, SleepingKnockerUpper] = {
    for {
      stateRef  <- Resource.make(
                     AtomicCell[IO].of(Map.empty[WorkflowInstanceId, Entry]),
                   )(_.get.flatMap(_.values.toList.parTraverse(_.fiber.cancel).void))
      wakeupRef <- AtomicCell[IO].of[Option[WorkflowInstanceId => IO[Unit]]](None).toResource
    } yield new SleepingKnockerUpper(stateRef, wakeupRef)
  }

}
