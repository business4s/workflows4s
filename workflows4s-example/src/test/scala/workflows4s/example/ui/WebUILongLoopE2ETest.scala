package workflows4s.example.ui

import cats.effect.IO
import cats.effect.kernel.Resource
import cats.effect.unsafe.implicits.global
import io.circe.Encoder
import org.http4s.HttpRoutes
import org.http4s.server.middleware.CORS
import org.openqa.selenium.By
import org.scalatest.time.{Seconds, Span}
import sttp.tapir.server.http4s.Http4sServerInterpreter
import workflows4s.runtime.cats.effect.InMemoryConcurrentRuntime
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine
import workflows4s.runtime.registry.InMemoryWorkflowRegistry
import workflows4s.runtime.wakeup.NoOpKnockerUpper
import workflows4s.web.api.server.{SignalSupport, WorkflowEntry, WorkflowServerEndpoints}
import workflows4s.wio.WorkflowContext

import scala.jdk.CollectionConverters.*

/** A workflow that polls in a loop. It produces an execution history with many iterations. */
object PollingWorkflow {
  case class State(polls: Int) derives Encoder

  enum Event {
    case Polled
  }

  object Context extends WorkflowContext {
    type Effect[T]      = IO[T]
    override type Event = PollingWorkflow.Event
    override type State = PollingWorkflow.State
  }
  import Context.*

  def workflow(iterations: Int): WIO.Initial = {
    val poll = WIO
      .runIO[State](_ => IO.pure(Event.Polled))
      .handleEvent((state, _) => state.copy(polls = state.polls + 1))
      .named("Poll Payout Status")
    val wait = WIO.pure.makeFrom[State].value(identity).named("Wait")
    WIO
      .repeat(poll)
      .until(_.polls >= iterations)
      .onRestart(wait)
      .named(conditionName = "Payout completed?", releaseBranchName = "Yes", restartBranchName = "No")
      .provideInput(State(0))
  }
}

/** Checks that the UI can show the progress of an instance with a long loop history. See https://github.com/business4s/workflows4s/issues/283.
  *
  * The test serves only the polling workflow. It does not use the example workflows of the demo server.
  */
class WebUILongLoopE2ETest extends WebUIE2EBase {
  override protected def serverPort: Int = 8092

  // Unrolled, this many iterations give a diagram far above the 50000 characters that Mermaid accepts by default.
  private val iterations = 1000
  private val instanceId = "long-polling"

  // The workflow has no timers, so no knocker-upper is needed.
  private val registry     = InMemoryWorkflowRegistry[IO]()
  private lazy val runtime = InMemoryConcurrentRuntime
    .default[IO, PollingWorkflow.Context.Ctx](
      workflow = PollingWorkflow.workflow(iterations),
      initialState = PollingWorkflow.State(0),
      engine = WorkflowInstanceEngine.default(NoOpKnockerUpper.agent[IO], registry),
      templateId = "polling",
    )
    .unsafeRunSync()

  override protected def apiRoutes: Resource[IO, HttpRoutes[IO]] = {
    val entry  = WorkflowEntry(
      name = "Polling",
      description = Some("Test workflow that polls in a loop many times."),
      runtime = runtime,
      stateEncoder = summon[Encoder[PollingWorkflow.State]],
      signalSupport = SignalSupport.builder.build,
    )
    val routes = Http4sServerInterpreter[IO]().toRoutes(WorkflowServerEndpoints.get[IO](List(entry), Some(registry)))
    Resource.pure(CORS.policy.withAllowOriginAll(routes))
  }

  "Web UI with a long loop" - {
    "should render the progress diagram of an instance with many loop iterations" taggedAs (E2E) in {
      val instance = runtime.createInstance(instanceId).unsafeRunSync()
      instance.wakeup().unsafeRunSync()
      instance.queryState().unsafeRunSync().polls shouldBe iterations

      driver.get(s"$baseUrl/ui/workflows/${runtime.templateId}/instances/$instanceId")

      eventually(timeout(Span(30, Seconds))) {
        val diagrams    = driver.findElements(By.cssSelector("svg-container svg")).asScala
        diagrams should not be empty
        val diagramText = diagrams.head.getText
        diagramText should not include "Maximum text size"
        diagramText should include(s"Payout completed? ×$iterations")
      }
    }
  }
}
