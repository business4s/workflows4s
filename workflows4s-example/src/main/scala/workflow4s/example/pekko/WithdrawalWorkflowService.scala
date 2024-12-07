package workflow4s.example.pekko

import cats.effect.IO
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem, Behavior}
import org.apache.pekko.cluster.sharding.typed.scaladsl.EntityRef
import org.apache.pekko.persistence.query.scaladsl.{CurrentPersistenceIdsQuery, PagedPersistenceIdsQuery, PersistenceIdsQuery, ReadJournal}
import org.apache.pekko.stream.scaladsl.Sink
import org.apache.pekko.util.Timeout
import workflow4s.example.withdrawal.WithdrawalSignal.CreateWithdrawal
import workflow4s.example.withdrawal.{WithdrawalData, WithdrawalSignal, WithdrawalWorkflow}
import workflow4s.runtime.WorkflowRuntime
import workflows4s.runtime.pekko.{PekkoRuntime, WorkflowBehavior}
import workflows4s.runtime.pekko.WorkflowBehavior.SignalResponse

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

trait WithdrawalWorkflowService {

  def startWorkflow(id: String, input: WithdrawalSignal.CreateWithdrawal): IO[Unit]

  def listWorkflows: IO[Seq[String]]

  def getState(id: String): IO[WithdrawalData]

  def cancelWithdrawal(id: String, request: WithdrawalSignal.CancelWithdrawal): Unit

  def markAsExecuted(id: String, request: WithdrawalSignal.ExecutionCompleted): Unit

}

object WithdrawalWorkflowService {
  type Journal = ReadJournal & CurrentPersistenceIdsQuery

  class Impl(journal: Journal, wdRuntime: PekkoRuntime[WithdrawalWorkflow.Context.Ctx])(implicit val actorSystem: ActorSystem[Any])
      extends WithdrawalWorkflowService {

    override def startWorkflow(id: String, input: CreateWithdrawal): IO[Unit] = {
      val workflow = wdRuntime.createInstance(id)
      IO.fromFuture(IO(workflow.deliverSignal(WithdrawalWorkflow.Signals.createWithdrawal, input)))
        .map({
          case Right(response) => response
          case Left(_)         => ??? // TODO error handling
        })
    }

    override def listWorkflows: IO[Seq[String]] = IO.fromFuture(IO(journal.currentPersistenceIds().runWith(Sink.seq)))

    override def getState(id: String): IO[WithdrawalData] = {
      val workflow = wdRuntime.createInstance(id)
      IO.fromFuture(IO(workflow.queryState()))
    }

    override def cancelWithdrawal(id: String, request: WithdrawalSignal.CancelWithdrawal): Unit = ???

    override def markAsExecuted(id: String, request: WithdrawalSignal.ExecutionCompleted): Unit = ???
  }

}
