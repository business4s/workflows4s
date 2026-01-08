package workflows4s.example.pekko

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.persistence.jdbc.query.scaladsl.JdbcReadJournal
import org.apache.pekko.persistence.jdbc.testkit.scaladsl.SchemaUtils
import org.apache.pekko.persistence.query.PersistenceQuery
import workflows4s.example.withdrawal.checks.LazyFutureChecksEngineHelper
import workflows4s.example.withdrawal.{FutureWithdrawalWorkflowHelper, WithdrawalData}
import workflows4s.runtime.instanceengine.{Effect, FutureEffect, LazyFuture, WorkflowInstanceEngine}
import workflows4s.runtime.pekko.PekkoRuntime
import workflows4s.runtime.wakeup.SleepingKnockerUpper

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.Duration

object Main extends App {

  given system: ActorSystem[Any] = ActorSystem(Behaviors.empty, "MyCluster")

  given ec: ExecutionContext = system.executionContext

  given futureEffect: Effect[LazyFuture] = FutureEffect.futureEffect

  val checksEngine = LazyFutureChecksEngineHelper.create()
  val service      = FutureDummyWithdrawalService()
  val workflow     = FutureWithdrawalWorkflowHelper.create(service, checksEngine)

  val knockerUpper = Await.result(SleepingKnockerUpper.create[LazyFuture].run, Duration.Inf)

  val engine = WorkflowInstanceEngine
    .builder[LazyFuture]
    .withJavaTime()
    .withWakeUps(knockerUpper)
    .withoutRegistering
    .withGreedyEvaluation
    .withLogging
    .get

  val runtime = PekkoRuntime.create[FutureWithdrawalWorkflowHelper.Context.Ctx](
    "withdrawal",
    workflow.workflowDeclarative,
    WithdrawalData.Empty,
    engine,
  )

  runtime.initializeShard()

  // Initialize knocker upper with wakeup logic
  val _initKnockerUpper = Await.result(
    knockerUpper.initialize(instanceId => runtime.createInstance_(instanceId.instanceId).wakeup()).run,
    Duration.Inf,
  )

  val journal                   = setupJournal()
  val withdrawalWorkflowService = new FutureWithdrawalWorkflowService.Impl(journal, runtime)
  val routes                    = new HttpRoutes(withdrawalWorkflowService)

  val bindingFuture = Http().newServerAt("localhost", 8989).bind(routes.routes)
  bindingFuture.foreach(binding => println(s"Server online at ${binding.localAddress}"))

  val _ = Await.result(system.whenTerminated, Duration.Inf)

  private def setupJournal(): JdbcReadJournal = {
    val journal = PersistenceQuery(system).readJournalFor[JdbcReadJournal](JdbcReadJournal.Identifier)
    val _       = Await.result(SchemaUtils.createIfNotExists(), Duration.Inf)
    journal
  }
}
