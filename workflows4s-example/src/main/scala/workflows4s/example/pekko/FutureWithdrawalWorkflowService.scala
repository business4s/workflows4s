package workflows4s.example.pekko

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.persistence.query.scaladsl.{CurrentPersistenceIdsQuery, ReadJournal}
import org.apache.pekko.stream.scaladsl.Sink
import workflows4s.example.withdrawal.{FutureWithdrawalWorkflowHelper, WithdrawalData, WithdrawalSignal, WithdrawalWorkflow}
import workflows4s.example.withdrawal.WithdrawalSignal.CreateWithdrawal
import workflows4s.runtime.WorkflowInstance.UnexpectedSignal
import workflows4s.runtime.pekko.PekkoRuntime

import scala.concurrent.{ExecutionContext, Future}

/** Future-based version of WithdrawalWorkflowService for use with FutureEffect.
  */
trait FutureWithdrawalWorkflowService {

  def startWorkflow(id: String, input: WithdrawalSignal.CreateWithdrawal): Future[Unit]

  def listWorkflows: Future[Seq[String]]

  def getState(id: String): Future[WithdrawalData]

  def cancelWithdrawal(id: String, request: WithdrawalSignal.CancelWithdrawal): Future[Unit]

  def markAsExecuted(id: String, request: WithdrawalSignal.ExecutionCompleted): Future[Unit]

}

object FutureWithdrawalWorkflowService {
  type Journal = ReadJournal & CurrentPersistenceIdsQuery

  class Impl(journal: Journal, wdRuntime: PekkoRuntime[FutureWithdrawalWorkflowHelper.Context.Ctx])(using
      val actorSystem: ActorSystem[Any],
      ec: ExecutionContext,
  ) extends FutureWithdrawalWorkflowService {

    override def startWorkflow(id: String, input: CreateWithdrawal): Future[Unit] = {
      val workflow = wdRuntime.createInstance_(id)
      workflow
        .deliverSignal(WithdrawalWorkflow.Signals.createWithdrawal, input)
        .run
        .map({
          case Right(response)             => response
          case Left(UnexpectedSignal(sig)) => throw new Exception(s"Unexpected creation signal $sig for instance ${id}")
        })
    }

    override def listWorkflows: Future[Seq[String]] = journal.currentPersistenceIds().runWith(Sink.seq)

    override def getState(id: String): Future[WithdrawalData] = {
      val workflow = wdRuntime.createInstance_(id)
      workflow.queryState().run
    }

    override def cancelWithdrawal(id: String, request: WithdrawalSignal.CancelWithdrawal): Future[Unit] = ???

    override def markAsExecuted(id: String, request: WithdrawalSignal.ExecutionCompleted): Future[Unit] = ???
  }

}
