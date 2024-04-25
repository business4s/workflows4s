package workflow4s.example.pekko

import cats.effect.IO
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem, Behavior}
import org.apache.pekko.persistence.query.scaladsl.{CurrentPersistenceIdsQuery, PagedPersistenceIdsQuery, PersistenceIdsQuery, ReadJournal}
import org.apache.pekko.stream.scaladsl.Sink
import workflow4s.example.withdrawal.WithdrawalSignal.CreateWithdrawal
import workflow4s.example.withdrawal.{WithdrawalData, WithdrawalSignal, WithdrawalWorkflow}
import workflows4s.runtime.pekko.WorkflowBehavior

trait WithdrawalWorkflowService {

  type WithdrawalActor = ActorRef[WorkflowBehavior.Command[WithdrawalWorkflow.Context.type]]

  def startWorkflow(id: String, input: WithdrawalSignal.CreateWithdrawal): WithdrawalActor

  def listWorkflows: IO[Seq[String]]

  def getState(id: String): WithdrawalData

  def cancelWithdrawal(id: String, request: WithdrawalSignal.CancelWithdrawal): Unit

  def markAsExecuted(id: String, request: WithdrawalSignal.ExecutionCompleted): Unit

}

object WithdrawalWorkflowService {
  type Journal = ReadJournal with CurrentPersistenceIdsQuery

  class Impl(journal: Journal)(implicit val actorSystem: ActorSystem[Any]) extends WithdrawalWorkflowService {

    override def startWorkflow(id: String, input: CreateWithdrawal): WithdrawalActor = ???

    override def listWorkflows: IO[Seq[String]] = IO.fromFuture(IO(journal.currentPersistenceIds().runWith(Sink.seq)))

    override def getState(id: String): WithdrawalData = ???

    override def cancelWithdrawal(id: String, request: WithdrawalSignal.CancelWithdrawal): Unit = ???

    override def markAsExecuted(id: String, request: WithdrawalSignal.ExecutionCompleted): Unit = ???
  }

}
