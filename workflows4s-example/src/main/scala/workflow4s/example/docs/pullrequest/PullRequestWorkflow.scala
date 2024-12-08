package workflow4s.example.docs.pullrequest

import java.io.File

import cats.effect.IO
import org.camunda.bpm.model.bpmn.Bpmn
import workflow4s.bpmn.BPMNConverter
import workflow4s.runtime.InMemorySyncRuntime
import workflow4s.wio
import workflow4s.wio.{SignalDef, WorkflowContext}

object PullRequestWorkflow {

  // start_state
  sealed trait PRState
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
    case class CreateRequest(commit: String)
    case class ReviewRequest(approve: Boolean)
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
  sealed trait PRError
  object PRError {
    case object CommitNotFound extends PRError
    case object PipelineFailed extends PRError
    case object ReviewRejected extends PRError
  }
  // end_error

  // start_context
  object Context extends WorkflowContext {
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
        if (evt.commit.length > 8) Left(PRError.CommitNotFound)
        else Right(PRState.Initiated(evt.commit)),
      )
      .voidResponse
      .autoNamed()
  // end_steps_1

  // start_steps_2
  val runPipeline: WIO[PRState.Initiated, PRError.PipelineFailed.type, PRState.Checked] =
    WIO
      .runIO[PRState.Initiated](in => IO(PREvent.Checked("<Some tests results>")))
      .handleEventWithError((in, evt) =>
        if (evt.pipelineResults.contains("error")) Left(PRError.PipelineFailed)
        else Right(PRState.Checked(in.commit, evt.pipelineResults)),
      )
      .autoNamed

  val awaitReview: WIO[PRState.Checked, PRError.ReviewRejected.type, PRState.Reviewed] =
    WIO
      .handleSignal(Signals.reviewPR)
      .using[PRState.Checked]
      .purely((in, req) => PREvent.Reviewed(req.approve))
      .handleEventWithError((in, evt) =>
        if (evt.approved) Right(PRState.Reviewed(in.commit, in.pipelineResults, evt.approved))
        else Left(PRError.ReviewRejected),
      )
      .voidResponse
      .autoNamed()
  // end_steps_2

  // start_steps_3
  val mergePR: WIO[PRState.Reviewed, Nothing, PRState.Merged]   =
    WIO.pure[PRState.Reviewed].make(in => in).autoNamed()
  val closePR: WIO[(PRState, PRError), Nothing, PRState.Closed] =
    WIO.pure[(PRState, PRError)].make((state, err) => PRState.Closed(state, err)).autoNamed()

  val workflow: WIO[Any, Nothing, PRState] = (
    createPR >>>
      runPipeline >>>
      awaitReview >>>
      mergePR
  ).handleErrorWith(closePR)
  // end_steps_3

  def main(args: Array[String]): Unit = {
    // start_render
    val bpmnModel = BPMNConverter.convert(workflow.getModel, "process")
    Bpmn.writeModelToFile(new File(s"pr.bpmn").getAbsoluteFile, bpmnModel)
    // end_render

    // start_execution
    val runtime    = InMemorySyncRuntime.default[Context.Ctx, PRState.Empty.type](workflow)
    val wfInstance = runtime.createInstance((), PRState.Empty)

    wfInstance.deliverSignal(Signals.createPR, Signals.CreateRequest("some-sha"))
    println(wfInstance.queryState())
    // Checked(some-sha,<Some tests results>)

    wfInstance.deliverSignal(Signals.reviewPR, Signals.ReviewRequest(approve = false))
    println(wfInstance.queryState())
    // Closed(Checked(some-sha,<Some tests results>),ReviewRejected)
    // end_execution

    // start_recovery
    val recoveredInstance = runtime.createInstance((), PRState.Empty)
    recoveredInstance.recover(wfInstance.getEvents)
    assert(wfInstance.queryState() == recoveredInstance.queryState())
    // end_recovery

  }

}
