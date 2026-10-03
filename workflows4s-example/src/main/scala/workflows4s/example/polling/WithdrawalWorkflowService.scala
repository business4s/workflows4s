package workflows4s.example.polling

import cats.effect.IO
import workflows4s.example.withdrawal.{WithdrawalData, WithdrawalWorkflow}
import workflows4s.example.withdrawal.WithdrawalSignal.CreateWithdrawal
import workflows4s.runtime.{InMemorySyncRuntime, WorkflowInstance}

trait WithdrawalWorkflowService {

  def startWorkflow(id: String, input: CreateWithdrawal): IO[Unit]

  def listWorkflows: IO[Seq[String]]

  def getState(id: String): IO[WithdrawalData]

}

object WithdrawalWorkflowService {

  class Impl(runtime: InMemorySyncRuntime[WithdrawalWorkflow.Context.Ctx]) extends WithdrawalWorkflowService {

    override def startWorkflow(id: String, input: CreateWithdrawal): IO[Unit] = {
      IO {
        val instance = runtime.createInstance(id)
        instance.deliverSignal(WithdrawalWorkflow.Signals.createWithdrawal, input) match {
          case Right(_)                                     => ()
          case Left(WorkflowInstance.UnexpectedSignal(sig)) =>
            throw new Exception(s"Unexpected creation signal $sig for instance $id")
        }
      }
    }

    override def listWorkflows: IO[Seq[String]] = IO {
      import scala.jdk.CollectionConverters.*
      runtime.instances.keys().asScala.toSeq
    }

    override def getState(id: String): IO[WithdrawalData] = IO {
      Option(runtime.instances.get(id)) match {
        case Some(instance) => instance.queryState()
        case None           => throw new NoSuchElementException(s"Workflow instance $id not found")
      }
    }

  }

}
