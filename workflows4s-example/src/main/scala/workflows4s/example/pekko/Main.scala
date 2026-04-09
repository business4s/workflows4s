package workflows4s.example.pekko

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.persistence.jdbc.query.scaladsl.JdbcReadJournal
import org.apache.pekko.persistence.jdbc.testkit.scaladsl.SchemaUtils
import org.apache.pekko.persistence.query.PersistenceQuery
import workflows4s.example.withdrawal.checks.ChecksEngine
import workflows4s.example.withdrawal.{WithdrawalData, WithdrawalWorkflow}
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine
import workflows4s.runtime.pekko.{PekkoKnockerUpper, PekkoRuntime}
import workflows4s.wio.LiftWorkflowEffect

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.Duration

object Main {

  def main(args: Array[String]): Unit = {
    given system: ActorSystem[Any] = ActorSystem(Behaviors.empty, "MyCluster")
    given ExecutionContext          = system.executionContext
    given IORuntime                 = IORuntime.global

    given LiftWorkflowEffect[WithdrawalWorkflow.Context.Ctx, Future] =
      LiftWorkflowEffect.through[WithdrawalWorkflow.Context.Ctx, IO](
        [A] => (fa: IO[A]) => fa.unsafeToFuture()(using IORuntime.global),
      )

    val knockerUpper = PekkoKnockerUpper.create
    val journal      = setupJournal()
    val workflow     = WithdrawalWorkflow(DummyWithdrawalService, ChecksEngine)
    val engine       =
      WorkflowInstanceEngine.builder
        .withJavaTime[Future]()
        .withWakeUps(knockerUpper)
        .withoutRegistering
        .withGreedyEvaluation
        .withLogging
        .get[WithdrawalWorkflow.Context.Ctx]
    val runtime      =
      PekkoRuntime.create(
        "withdrawal",
        workflow.workflowDeclarative,
        WithdrawalData.Empty,
        engine,
      )

    runtime.initializeShard()
    knockerUpper.initialize(Seq(runtime))

    val withdrawalWorkflowService = WithdrawalWorkflowService.Impl(journal, runtime)
    val routes                    = HttpRoutes(withdrawalWorkflowService)

    val binding = Await.result(Http().newServerAt("localhost", 8989).bind(routes.routes), Duration.Inf)
    println(s"Server online at ${binding.localAddress}")

    val _ = Await.result(system.whenTerminated, Duration.Inf)
  }

  private def setupJournal()(using system: ActorSystem[Any]): JdbcReadJournal = {
    val journal = PersistenceQuery(system).readJournalFor[JdbcReadJournal](JdbcReadJournal.Identifier)
    val _ = Await.result(SchemaUtils.createIfNotExists()(using system), Duration.Inf)
    journal
  }
}
