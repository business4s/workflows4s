package workflows4s.runtime.wakeup

import java.time.{Duration, Instant}
import scala.jdk.DurationConverters.JavaDurationOps
import com.typesafe.scalalogging.StrictLogging
import workflows4s.runtime.WorkflowInstanceId
import workflows4s.runtime.instanceengine.{Effect, Fiber, Outcome, Ref}
import workflows4s.runtime.instanceengine.Effect.*

/** Simple implementation for KnockerUpper that relies on Effect.sleep.
  */
class SleepingKnockerUpper[F[_]](
    state: Ref[F, Map[WorkflowInstanceId, (Instant, Fiber[F, Unit])]],
    wakeupLogicRef: Ref[F, Option[WorkflowInstanceId => F[Unit]]],
)(using E: Effect[F])
    extends KnockerUpper.Agent[F]
    with KnockerUpper.Process[F, F[Unit]]
    with StrictLogging {

  override def updateWakeup(id: WorkflowInstanceId, at: Option[Instant]): F[Unit] = {
    at match {
      case Some(wakeupTime) =>
        for {
          wakeupOpt  <- wakeupLogicRef.get
          wakeup     <- E.fromOption(wakeupOpt, new Exception("No wakeup logic registered. Please call start before calling updateWakeup."))
          sleepFiber <- E.start(sleepAndWakeup(id, wakeupTime, wakeup))
          prevState  <- state.getAndUpdate(_.updated(id, (wakeupTime, sleepFiber)))
          _          <- E.traverse_(prevState.get(id).toList)(_._2.cancel)
        } yield ()
      case None             =>
        for {
          prevState <- state.getAndUpdate(_.removed(id))
          _         <- E.traverse_(prevState.get(id).toList)(_._2.cancel)
        } yield ()
    }
  }

  private def sleepAndWakeup(id: WorkflowInstanceId, at: Instant, wakeup: WorkflowInstanceId => F[Unit]): F[Unit] = {
    E.guaranteeCase(
      for {
        now     <- E.delay(Instant.now())
        duration = Duration.between(now, at).toScala
        _       <- E.delay(logger.debug(s"Sleeping for $duration before waking up $id"))
        _       <- E.sleep(duration)
        _       <- E.delay(logger.debug(s"Waking up ${id}"))
        _       <- wakeup(id)
      } yield (),
    ) {
      case Outcome.Succeeded(_) => E.delay(logger.debug(s"Sleep for $id completed")) *> removeSpecific(id, at)
      case Outcome.Errored(e)   => E.delay(logger.debug(s"Failed to wake up $id: ${e.getMessage}")) *> removeSpecific(id, at)
      case Outcome.Canceled     => removeSpecific(id, at)
    }
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
    wakeupLogicRef.get.flatMap {
      case Some(_) => E.raiseError(new Exception("Start can be called only once"))
      case None    => wakeupLogicRef.set(Some(wakeUp))
    }
  }

}

object SleepingKnockerUpper {

  def create[F[_]](using E: Effect[F]): F[SleepingKnockerUpper[F]] = {
    for {
      stateRef  <- E.ref(Map.empty[WorkflowInstanceId, (Instant, Fiber[F, Unit])])
      wakeupRef <- E.ref(Option.empty[WorkflowInstanceId => F[Unit]])
    } yield new SleepingKnockerUpper(stateRef, wakeupRef)
  }

}
