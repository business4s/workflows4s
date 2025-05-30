package workflows4s.runtime.registry

import cats.effect.IO

object WorkflowRegistry {

  enum ExecutionStatus {
    case Running, Awaiting, Finished
  }

  trait Agent[-Id] {

    def upsertInstance(id: Id, executionStatus: ExecutionStatus): IO[Unit]

    def curried(id: Id): Agent.Curried = {
      val self = this
      val id0  = id
      (_: Any, executionStatus: ExecutionStatus) => self.upsertInstance(id0, executionStatus)
    }
  }

  object Agent {
    type Curried = Agent[Unit]
  }

}
