package workflow4s.example.pekko

import cats.effect.unsafe.implicits.global
import cats.effect.{ExitCode, IO, IOApp}
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.persistence.jdbc.query.scaladsl.JdbcReadJournal
import org.apache.pekko.persistence.jdbc.testkit.scaladsl.SchemaUtils
import org.apache.pekko.persistence.query.PersistenceQuery
import workflow4s.example.withdrawal.WithdrawalWorkflow
import workflow4s.example.withdrawal.checks.ChecksEngine

object Main extends IOApp {

  // TODO proper termination
  override def run(args: List[String]): IO[ExitCode] = {
    implicit val system: ActorSystem[Any] = ActorSystem(Behaviors.empty, "MyCluster")

    for {
      journal                  <- setupJournal()
      shard                     = {
        val workflow = WithdrawalWorkflow(DummyWithdrawalService, ChecksEngine)
        WithdrawalShard.create(workflow)
      }
      withdrawalWorkflowService = WithdrawalWorkflowService.Impl(journal, shard)
      routes                    = HttpRoutes(system, withdrawalWorkflowService)
      _                        <- runHttpServer(routes)
      _                        <- IO.fromFuture(IO(system.whenTerminated))
    } yield ExitCode.Success
  }

  private def runHttpServer(routes: HttpRoutes)(implicit system: ActorSystem[Any]): IO[Http.ServerBinding] =
    IO.fromFuture(IO(Http().newServerAt("localhost", 8989).bind(routes.routes)))
      .flatTap(binding => IO(println(s"Server online at ${binding.localAddress}")))

  private def setupJournal()(implicit system: ActorSystem[Any]): IO[JdbcReadJournal] = {
    val journal = PersistenceQuery(system).readJournalFor[JdbcReadJournal](JdbcReadJournal.Identifier)
    IO.fromFuture(IO(SchemaUtils.createIfNotExists())).as(journal)
  }
}
