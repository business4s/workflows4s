package workflows4s.runtime.registry

import cats.effect.{IO, Ref}
import com.typesafe.scalalogging.StrictLogging
import workflows4s.runtime.WorkflowInstanceId
import workflows4s.runtime.registry.WorkflowRegistry.ExecutionStatus

import java.time.{Clock, Instant}

trait InMemoryWorkflowRegistry extends WorkflowRegistry.Agent {

  def getWorkflows(): IO[List[InMemoryWorkflowRegistry.Data]]

}

object InMemoryWorkflowRegistry {

  case class Data(id: WorkflowInstanceId, createdAt: Instant, updatedAt: Instant, status: ExecutionStatus)

  def apply(clock: Clock = Clock.systemUTC()): IO[InMemoryWorkflowRegistry] = {
    Ref[IO].of(Map.empty[WorkflowInstanceId, Data]).map { stateRef =>
      new Impl(stateRef, clock)
    }
  }

  private class Impl(
      stateRef: Ref[IO, Map[WorkflowInstanceId, Data]],
      clock: Clock,
  ) extends InMemoryWorkflowRegistry
      with StrictLogging {

    override def upsertInstance(id: WorkflowInstanceId, executionStatus: ExecutionStatus): IO[Unit] = {
      for {
        now <- IO(Instant.now(clock))
        _    = logger.info(s"Updating workflow registry for ${id} to status $executionStatus at $now")
        _   <- stateRef.update { state =>
                 state.get(id) match {
                   case Some(existing) =>
                     if existing.updatedAt.isAfter(now) then state
                     else state + (id -> existing.copy(updatedAt = now, status = executionStatus))
                   case None           =>
                     state + (id -> Data(id, now, now, executionStatus))
                 }
               }
      } yield ()
    }

    override def getWorkflows(): IO[List[Data]] = stateRef.get.map(_.values.toList)
  }

}
