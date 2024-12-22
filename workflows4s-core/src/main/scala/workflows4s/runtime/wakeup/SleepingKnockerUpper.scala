package workflows4s.runtime.wakeup

import cats.effect.kernel.Outcome
import cats.effect.std.AtomicCell
import cats.effect.{FiberIO, IO, Resource}
import cats.implicits.{catsSyntaxApplicativeId, catsSyntaxOptionId, toTraverseOps}
import com.typesafe.scalalogging.StrictLogging

import java.time.{Duration, Instant}
import scala.jdk.DurationConverters.JavaDurationOps

/** Simple implementation for KnockerUpper that relies on IO.sleep.
  */
class SleepingKnockerUpper[Id](
    state: AtomicCell[IO, Map[Id, (Instant, FiberIO[Unit])]],
    wakeupLogicRef: AtomicCell[IO, Option[Id => IO[Unit]]],
) extends KnockerUpper.Agent[Id]
    with KnockerUpper.Process[IO, Id, IO[Unit]]
    with StrictLogging {

  override def updateWakeup(id: Id, at: Option[Instant]): IO[Unit] = {
    at match {
      case Some(wakeupTime) =>
        for {
          wakeupOpt  <- wakeupLogicRef.get
          wakeup     <- IO.fromOption(wakeupOpt)(new Exception("No wakeup logic registered. Please call start before calling updateWakeup."))
          sleepFiber <- sleepAndWakeup(id, wakeupTime, wakeup).start
          prevState  <- state.getAndUpdate(_.updated(id, (wakeupTime, sleepFiber)))
          _          <- prevState.get(id).traverse(_._2.cancel)
        } yield ()
      case None             =>
        for {
          prevState <- state.getAndUpdate(_.removed(id))
          _         <- prevState.get(id).traverse(_._2.cancel)
        } yield ()
    }
  }

  private def sleepAndWakeup(id: Id, at: Instant, wakeup: Id => IO[Unit]) = {
    (for {
      now     <- IO(Instant.now())
      duration = Duration.between(now, at).toScala
      _       <- IO(logger.debug(s"Sleeping for $duration before waking up $id"))
      _       <- IO.sleep(duration)
      _       <- IO(logger.debug(s"Waking up ${id}"))
      _       <- wakeup(id)
    } yield ())
      .guaranteeCase({
        case Outcome.Succeeded(_) => IO(logger.debug(s"Sleep for $id cancelled")) *> removeSpecific(id, at)
        case Outcome.Errored(e)   => IO(logger.debug(s"Failed to wake up $id"), e) *> removeSpecific(id, at)
        case Outcome.Canceled()   => removeSpecific(id, at)
      })
  }

  private def removeSpecific(id: Id, at: Instant): IO[Unit] = {
    state.update(st => {
      st.get(id) match {
        case Some((`at`, _)) => st.removed(id)
        case None | Some(_)  => st
      }
    })
  }

  override def initialize(wakeUp: Id => IO[Unit]): IO[Unit] = {
    wakeupLogicRef.evalUpdate({
      case Some(_) => IO.raiseError(new Exception("Start can be called only once"))
      case None    => wakeUp.some.pure[IO]
    })
  }

}

object SleepingKnockerUpper {

  def create[Id](): Resource[IO, SleepingKnockerUpper[Id]] = {
    for {
      stateRef  <- Resource.make(
                     AtomicCell[IO].of(Map.empty[Id, (Instant, FiberIO[Unit])]),
                   )(_.get.flatMap(_.values.toList.traverse(_._2.cancel).void))
      wakeupRef <- AtomicCell[IO].of[Option[Id => IO[Unit]]](None).toResource
    } yield new SleepingKnockerUpper(stateRef, wakeupRef)
  }

}
