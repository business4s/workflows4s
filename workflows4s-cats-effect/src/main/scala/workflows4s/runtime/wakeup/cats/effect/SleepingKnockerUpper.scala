package workflows4s.runtime.wakeup.cats.effect

import java.time.{Duration, Instant}
import scala.jdk.DurationConverters.JavaDurationOps
import _root_.cats.effect.kernel.Outcome
import _root_.cats.effect.std.AtomicCell
import _root_.cats.effect.syntax.all.*
import _root_.cats.effect.{Async, Fiber, Resource}
import _root_.cats.implicits.{catsSyntaxApplicativeId, catsSyntaxOptionId, toTraverseOps}
import _root_.cats.syntax.flatMap.*
import _root_.cats.syntax.functor.*
import com.typesafe.scalalogging.StrictLogging
import workflows4s.runtime.WorkflowInstanceId
import workflows4s.runtime.wakeup.KnockerUpper

/** Simple implementation for KnockerUpper that relies on Async[F].sleep.
  */
class SleepingKnockerUpper[F[_]: Async](
    state: AtomicCell[F, Map[WorkflowInstanceId, (Instant, Fiber[F, Throwable, Unit])]],
    wakeupLogicRef: AtomicCell[F, Option[WorkflowInstanceId => F[Unit]]],
) extends KnockerUpper.Agent[F]
    with KnockerUpper.Process[F, F[Unit]]
    with StrictLogging {

  override def updateWakeup(id: WorkflowInstanceId, at: Option[Instant]): F[Unit] = {
    at match {
      case Some(wakeupTime) =>
        for {
          wakeupOpt  <- wakeupLogicRef.get
          wakeup     <- Async[F].fromOption(wakeupOpt, new Exception("No wakeup logic registered. Please call start before calling updateWakeup."))
          sleepFiber <- Async[F].start(sleepAndWakeup(id, wakeupTime, wakeup))
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

  private def sleepAndWakeup(id: WorkflowInstanceId, at: Instant, wakeup: WorkflowInstanceId => F[Unit]) = {
    (for {
      now     <- Async[F].delay(Instant.now())
      duration = Duration.between(now, at).toScala
      _       <- Async[F].delay(logger.debug(s"Sleeping for $duration before waking up $id"))
      _       <- Async[F].sleep(duration)
      _       <- Async[F].delay(logger.debug(s"Waking up ${id}"))
      _       <- wakeup(id)
    } yield ())
      .guaranteeCase({
        case Outcome.Canceled() => Async[F].delay(logger.debug(s"Sleep for $id cancelled")) >> removeSpecific(id, at)
        case Outcome.Errored(e)   => Async[F].delay(logger.debug(s"Failed to wake up $id", e)) >> removeSpecific(id, at)
        case Outcome.Succeeded(_)   => removeSpecific(id, at)
      })
  }

  private def removeSpecific(id: WorkflowInstanceId, at: Instant): F[Unit] = {
    state.update(st => {
      st.get(id) match {
        case Some((`at`, _)) => st.removed(id)
        case None | Some(_)  => st
      }
    })
  }

  override def initialize(wakeUp: WorkflowInstanceId => F[Unit]): F[Unit] = {
    wakeupLogicRef.evalUpdate({
      case Some(_) => Async[F].raiseError(new Exception("Start can be called only once"))
      case None    => wakeUp.some.pure[F]
    })
  }

}

object SleepingKnockerUpper {

  def create[F[_]: Async](): Resource[F, SleepingKnockerUpper[F]] = {
    for {
      stateRef  <- Resource.make(
                     AtomicCell[F].of(Map.empty[WorkflowInstanceId, (Instant, Fiber[F, Throwable, Unit])]),
                   )(_.get.flatMap(_.values.toList.traverse(_._2.cancel).void))
      wakeupRef <- AtomicCell[F].of[Option[WorkflowInstanceId => F[Unit]]](None).toResource
    } yield new SleepingKnockerUpper[F](stateRef, wakeupRef)
  }

}
