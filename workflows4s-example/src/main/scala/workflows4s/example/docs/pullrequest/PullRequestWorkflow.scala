package workflows4s.example.docs.pullrequest

import cats.effect.IO
import io.circe.{Decoder, Encoder}
import org.camunda.bpm.model.bpmn.Bpmn
import sttp.tapir.Schema
import workflows4s.bpmn.BpmnRenderer
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine
import workflows4s.runtime.{InMemoryRuntime, InMemoryWorkflowInstance}
import workflows4s.cats.CatsEffect.given
import workflows4s.cats.IOWorkflowContext
import workflows4s.wio.SignalDef
import cats.effect.unsafe.implicits.global

import java.nio.file.Files
import scala.annotation.nowarn

@nowarn("msg=unused explicit parameter")
object PullRequestWorkflow {

  // start_state
  sealed trait PRState derives Encoder
  object PRState {
    case object Empty                                                               extends PRState
    case class Initiated(commit: String)                                            extends PRState
    case class Checked(commit: String, pipelineResults: String)                     extends PRState
    case class Reviewed(commit: String, pipelineResults: String, approved: Boolean) extends PRState
    type Merged = Reviewed
    case class Closed(state: PRState, reason: PRError) extends PRState
  }
  // end_state

  // start_signals
  object Signals {
    val createPR: SignalDef[CreateRequest, Unit] = SignalDef()
    val reviewPR: SignalDef[ReviewRequest, Unit] = SignalDef()
    case class CreateRequest(commit: String) derives Schema, Decoder
    case class ReviewRequest(approve: Boolean) derives Schema, Decoder
  }
  // end_signals

  // start_events
  sealed trait PREvent
  object PREvent {
    case class Created(commit: String)          extends PREvent
    case class Checked(pipelineResults: String) extends PREvent
    case class Reviewed(approved: Boolean)      extends PREvent
  }
  // end_events

  // start_error
  sealed trait PRError derives Encoder
  object PRError {
    case object CommitNotFound extends PRError
    case object PipelineFailed extends PRError
    case object ReviewRejected extends PRError
  }
  // end_error

  // start_context
  object Context extends IOWorkflowContext {
    override type Event = PREvent
    override type State = PRState
  }
  import Context.*
  // end_context

  // start_steps_1
  val createPR: WIO[Any, PRError.CommitNotFound.type, PRState.Initiated] =
    WIO
      .handleSignal(Signals.createPR)
      .using[Any]
      .purely((in, req) => PREvent.Created(req.commit))
      .handleEventWithError((in, evt) =>
        if evt.commit.length > 8 then Left(PRError.CommitNotFound)
        else Right(PRState.Initiated(evt.commit)),
      )
      .voidResponse
      .autoNamed
  // end_steps_1

  // start_steps_2
  val runPipeline: WIO[PRState.Initiated, PRError.PipelineFailed.type, PRState.Checked] =
    WIO
      .runIO[PRState.Initiated](in => IO(PREvent.Checked("<Some tests results>")))
      .handleEventWithError((in, evt) =>
        if evt.pipelineResults.contains("error") then Left(PRError.PipelineFailed)
        else Right(PRState.Checked(in.commit, evt.pipelineResults)),
      )
      .autoNamed()

  val processReview: WIO[PRState.Checked, PRError.ReviewRejected.type, PRState.Reviewed] =
    WIO
      .handleSignal(Signals.reviewPR)
      .using[PRState.Checked]
      .purely((in, req) => PREvent.Reviewed(req.approve))
      .handleEventWithError((in, evt) =>
        if evt.approved then Right(PRState.Reviewed(in.commit, in.pipelineResults, evt.approved))
        else Left(PRError.ReviewRejected),
      )
      .voidResponse
      .autoNamed
  // end_steps_2

  // start_steps_3
  val mergePR: WIO[PRState.Reviewed, Nothing, PRState.Merged]   =
    WIO.pure.makeFrom[PRState.Reviewed].value(identity).autoNamed
  val closePR: WIO[(PRState, PRError), Nothing, PRState.Closed] =
    WIO.pure.makeFrom[(PRState, PRError)].value((state, err) => PRState.Closed(state, err)).autoNamed

  val workflow: WIO.Initial = (
    createPR >>>
      runPipeline >>>
      processReview >>>
      mergePR
  ).handleErrorWith(closePR)
  // end_steps_3

  @nowarn("msg=unused value")
  def run: InMemoryWorkflowInstance[IO, Ctx] = {

    val file      = Files.createTempFile("pr", ".bpmn").toFile
    // start_render
    val bpmnModel = BpmnRenderer.renderWorkflow(workflow.toProgress.toModel, "process")
    Bpmn.writeModelToFile(file, bpmnModel)
    // end_render

    // start_execution
    val engine     = WorkflowInstanceEngine.basic[IO]()
    val runtime    = InMemoryRuntime.create[IO, Context.Ctx](workflow, PRState.Empty, engine).unsafeRunSync()
    val wfInstance = runtime.createInMemoryInstance("id").unsafeRunSync()

    wfInstance.deliverSignal(Signals.createPR, Signals.CreateRequest("some-sha")).unsafeRunSync()
    println(wfInstance.queryState().unsafeRunSync())
    // Checked(some-sha,<Some tests results>)

    wfInstance.deliverSignal(Signals.reviewPR, Signals.ReviewRequest(approve = false)).unsafeRunSync()
    println(wfInstance.queryState().unsafeRunSync())
    // Closed(Checked(some-sha,<Some tests results>),ReviewRejected)
    // end_execution

    // start_recovery
    val recoveredInstance = runtime.createInMemoryInstance("id").unsafeRunSync()
    recoveredInstance.recover(wfInstance.getEvents.unsafeRunSync()).unsafeRunSync()
    assert(wfInstance.queryState().unsafeRunSync() == recoveredInstance.queryState().unsafeRunSync())
    // end_recovery

    wfInstance
  }

}
