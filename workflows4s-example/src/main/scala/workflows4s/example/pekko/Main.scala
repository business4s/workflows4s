package workflows4s.example.pekko

import cats.effect.unsafe.implicits.global
import cats.effect.{ExitCode, IO, IOApp, Resource}
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.persistence.jdbc.query.scaladsl.JdbcReadJournal
import org.apache.pekko.persistence.jdbc.testkit.scaladsl.SchemaUtils
import org.apache.pekko.persistence.query.PersistenceQuery
import workflows4s.cats.CatsEffect.given
import workflows4s.example.withdrawal.checks.ChecksEngine
import workflows4s.example.withdrawal.{WithdrawalData, WithdrawalWorkflow}
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine
import workflows4s.runtime.pekko.PekkoRuntime
import workflows4s.runtime.wakeup.SleepingKnockerUpper

object Main extends IOApp {
  override def run(args: List[String]): IO[ExitCode] = {
    given system: ActorSystem[Any] = ActorSystem(Behaviors.empty, "MyCluster")
    Resource
      .make(IO.unit)(_ => IO(system.terminate()))
      .use(_ =>
        for {
          knockerUpper             <- SleepingKnockerUpper.create[IO]
          journal                  <- setupJournal()
          workflow                  = WithdrawalWorkflow(DummyWithdrawalService, ChecksEngine)
          runtime                   =
            PekkoRuntime.create[WithdrawalWorkflow.Context.Ctx](
              "withdrawal",
              workflow.workflowDeclarative,
              WithdrawalData.Empty,
              WorkflowInstanceEngine.builder.withJavaTime().withWakeUps(knockerUpper).withoutRegistering.withGreedyEvaluation.withLogging.get,
            )
          _                        <- IO(runtime.initializeShard())
          _                        <- knockerUpper.initialize(wokeup => IO.println(s"Woke up! $wokeup"))
          withdrawalWorkflowService = WithdrawalWorkflowService.Impl(journal, runtime)
          routes                    = HttpRoutes(withdrawalWorkflowService)
          _                        <- runHttpServer(routes)
          _                        <- IO.fromFuture(IO(system.whenTerminated))
        } yield ExitCode.Success,
      )
  }

  private def runHttpServer(routes: HttpRoutes)(using system: ActorSystem[Any]): IO[Http.ServerBinding] =
    IO.fromFuture(IO(Http().newServerAt("localhost", 8989).bind(routes.routes)))
      .flatTap(binding => IO(println(s"Server online at ${binding.localAddress}")))

  private def setupJournal()(using system: ActorSystem[Any]): IO[JdbcReadJournal] = {
    val journal = PersistenceQuery(system).readJournalFor[JdbcReadJournal](JdbcReadJournal.Identifier)
    IO.fromFuture(IO(SchemaUtils.createIfNotExists())).as(journal)
  }
}
