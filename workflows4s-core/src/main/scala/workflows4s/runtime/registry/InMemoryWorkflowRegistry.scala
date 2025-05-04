package workflows4s.runtime.registry

import cats.effect.{IO, Ref}
import workflows4s.runtime.registry.WorkflowRegistry.ExecutionStatus

import java.time.{Clock, Instant}

trait InMemoryWorkflowRegistry[WorkflowId] {

  def getAgent(workflowType: String): WorkflowRegistry.Agent[WorkflowId]

  def getWorkflows(): IO[List[InMemoryWorkflowRegistry.Data[WorkflowId]]]

}

object InMemoryWorkflowRegistry {

  case class Data[WorkflowId](id: WorkflowId, workflowType: String, createdAt: Instant, updatedAt: Instant, status: ExecutionStatus)

  def apply[WorkflowId](clock: Clock = Clock.systemUTC()): IO[InMemoryWorkflowRegistry[WorkflowId]] = {
    Ref[IO].of(Map.empty[(String, WorkflowId), Data[WorkflowId]]).map { stateRef =>
      new Impl[WorkflowId](stateRef, clock)
    }
  }

  private class Impl[WorkflowId](
      stateRef: Ref[IO, Map[(String, WorkflowId), Data[WorkflowId]]],
      clock: Clock,
  ) extends InMemoryWorkflowRegistry[WorkflowId] {

    override def getAgent(workflowType: String): WorkflowRegistry.Agent[WorkflowId] = new WorkflowRegistry.Agent[WorkflowId] {
      override def upsertInstance(id: WorkflowId, executionStatus: ExecutionStatus): IO[Unit] = {
        for {
          now <- IO(Instant.now(clock))
          _ <- stateRef.update { state =>
            state.get((workflowType, id)) match {
              case Some(existing) =>
                if (existing.updatedAt.isBefore(now)) {
                  state + ((workflowType, id) -> existing.copy(updatedAt = now, status = executionStatus))
                } else state

              case None           =>
                state + ((workflowType, id) -> Data(id, workflowType, now, now, executionStatus))
            }
          }
        } yield ()
      }
    }

    override def getWorkflows(): IO[List[Data[WorkflowId]]] = {
      stateRef.get.map(_.values.toList)
    }
  }
}
