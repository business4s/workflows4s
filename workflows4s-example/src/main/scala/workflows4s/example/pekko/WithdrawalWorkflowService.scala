package workflows4s.example.pekko

import cats.effect.IO
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.persistence.query.scaladsl.{CurrentPersistenceIdsQuery, ReadJournal}
import org.apache.pekko.stream.scaladsl.Sink
import workflows4s.example.withdrawal.WithdrawalSignal.CreateWithdrawal
import workflows4s.example.withdrawal.{WithdrawalData, WithdrawalSignal, WithdrawalWorkflow}
import workflows4s.runtime.WorkflowInstance.UnexpectedSignal
import workflows4s.runtime.pekko.PekkoRuntime

trait WithdrawalWorkflowService {

  def startWorkflow(id: String, input: WithdrawalSignal.CreateWithdrawal): IO[Unit]

  def listWorkflows: IO[Seq[String]]

  def getState(id: String): IO[WithdrawalData]

  def cancelWithdrawal(id: String, request: WithdrawalSignal.CancelWithdrawal): Unit

  def markAsExecuted(id: String, request: WithdrawalSignal.ExecutionCompleted): Unit

}

object WithdrawalWorkflowService {
  type Journal = ReadJournal & CurrentPersistenceIdsQuery

  class Impl(journal: Journal, wdRuntime: PekkoRuntime[WithdrawalWorkflow.Context.Ctx])(using val actorSystem: ActorSystem[Any])
      extends WithdrawalWorkflowService {

    override def startWorkflow(id: String, input: CreateWithdrawal): IO[Unit] = {
      val workflow = wdRuntime.createInstance_(id)
      IO.fromFuture(IO(workflow.deliverSignal(WithdrawalWorkflow.Signals.createWithdrawal, input)))
        .map({
          case Right(response)             => response
          case Left(UnexpectedSignal(sig)) => throw new Exception(s"Unexpected creation signal $sig for instance ${id}")
        })
    }

    override def listWorkflows: IO[Seq[String]] = IO.fromFuture(IO(journal.currentPersistenceIds().runWith(Sink.seq)))

    override def getState(id: String): IO[WithdrawalData] = {
      val workflow = wdRuntime.createInstance_(id)
      IO.fromFuture(IO(workflow.queryState()))
    }

    override def cancelWithdrawal(id: String, request: WithdrawalSignal.CancelWithdrawal): Unit = ???

    override def markAsExecuted(id: String, request: WithdrawalSignal.ExecutionCompleted): Unit = ???
  }

}
