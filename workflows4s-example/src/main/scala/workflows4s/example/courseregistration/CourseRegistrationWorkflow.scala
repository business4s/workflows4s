package workflows4s.example.courseregistration

import java.io.File
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

import scala.annotation.nowarn

@nowarn("msg=unused explicit parameter")
object CourseRegistrationWorkflow {

  // start_state
  sealed trait CourseRegistrationState derives Encoder
  object RegistrationState {
    case object Empty                                                                                          extends CourseRegistrationState
    case class Browsing(studentId: String, semester: String, currentCR: String)                                extends CourseRegistrationState
    case class PrioritiesSet(studentId: String, semester: String, priorities: Map[String, List[String]])       extends CourseRegistrationState
    case class RegistrationComplete(studentId: String, semester: String, assignedCourses: Map[String, String]) extends CourseRegistrationState
    case class RegistrationFailed(state: CourseRegistrationState, reason: RegistrationError)                   extends CourseRegistrationState
  }
  // end_state

  // start_signals
  object Signals {
    val startBrowsing: SignalDef[BrowsingRequest, Unit] = SignalDef()
    val setPriorities: SignalDef[PriorityRequest, Unit] = SignalDef()
    case class BrowsingRequest(studentId: String, semester: String) derives Schema, Decoder
    case class PriorityRequest(courseRequirement: String, priorities: List[String]) derives Schema, Decoder
  }
  // end_signals

  // start_events
  sealed trait RegistrationEvent
  object RegistrationEvent {
    case class BrowsingStarted(studentId: String, semester: String)                     extends RegistrationEvent
    case class PrioritiesSubmitted(courseRequirement: String, priorities: List[String]) extends RegistrationEvent
    case class AllotmentProcessed(assignments: Map[String, String])                     extends RegistrationEvent
  }
  // end_events

  // start_error
  sealed trait RegistrationError derives Encoder
  object RegistrationError {
    case object RegistrationClosed extends RegistrationError
    case object InvalidPriorities  extends RegistrationError
    case object AllCoursesFull     extends RegistrationError
  }
  // end_error

  // start_context
  object Context extends IOWorkflowContext {
    override type Event = RegistrationEvent
    override type State = CourseRegistrationState
  }
  import Context.*
  // end_context

  // start_steps_1
  val startBrowsing: WIO[Any, RegistrationError.RegistrationClosed.type, RegistrationState.Browsing] =
    WIO
      .handleSignal(Signals.startBrowsing)
      .using[Any]
      .purely((in, req) => RegistrationEvent.BrowsingStarted(req.studentId, req.semester))
      .handleEventWithError((in, evt) =>
        if evt.semester == "closed" then Left(RegistrationError.RegistrationClosed)
        else Right(RegistrationState.Browsing(evt.studentId, evt.semester, "CR1")),
      )
      .voidResponse
      .autoNamed
  // end_steps_1

  // start_steps_2
  val setPriorities: WIO[RegistrationState.Browsing, RegistrationError.InvalidPriorities.type, RegistrationState.PrioritiesSet] =
    WIO
      .handleSignal(Signals.setPriorities)
      .using[RegistrationState.Browsing]
      .purely((in, req) => RegistrationEvent.PrioritiesSubmitted(req.courseRequirement, req.priorities))
      .handleEventWithError((in, evt) =>
        if evt.priorities.isEmpty then Left(RegistrationError.InvalidPriorities)
        else Right(RegistrationState.PrioritiesSet(in.studentId, in.semester, Map(evt.courseRequirement -> evt.priorities))),
      )
      .voidResponse
      .autoNamed

  val processAllotment: WIO[RegistrationState.PrioritiesSet, RegistrationError.AllCoursesFull.type, RegistrationState.RegistrationComplete] =
    WIO
      .runIO[RegistrationState.PrioritiesSet](in => IO(RegistrationEvent.AllotmentProcessed(Map("CR1" -> "CS101-Prof.Smith"))))
      .handleEventWithError((in, evt) =>
        if evt.assignments.isEmpty then Left(RegistrationError.AllCoursesFull)
        else Right(RegistrationState.RegistrationComplete(in.studentId, in.semester, evt.assignments)),
      )
      .autoNamed()
  // end_steps_2

  // start_steps_3
  val completeRegistration: WIO[RegistrationState.RegistrationComplete, Nothing, RegistrationState.RegistrationComplete] =
    WIO.pure.makeFrom[RegistrationState.RegistrationComplete].value(identity).autoNamed
  val handleFailure: WIO[(CourseRegistrationState, RegistrationError), Nothing, RegistrationState.RegistrationFailed]    =
    WIO.pure.makeFrom[(CourseRegistrationState, RegistrationError)].value((state, err) => RegistrationState.RegistrationFailed(state, err)).autoNamed

  val workflow: WIO.Initial = (
    startBrowsing >>>
      setPriorities >>>
      processAllotment >>>
      completeRegistration
  ).handleErrorWith(handleFailure)
  // end_steps_3

  @nowarn("msg=unused value")
  def run: InMemoryWorkflowInstance[IO, Context.Ctx] = {
    // start_render
    val bpmnModel = BpmnRenderer.renderWorkflow(workflow.toProgress.toModel, "course-registration")
    Bpmn.writeModelToFile(new File("course-registration.bpmn").getAbsoluteFile, bpmnModel)
    // end_render

    // start_execution
    val engine     = WorkflowInstanceEngine.basic[IO]()
    val runtime    = InMemoryRuntime.create[IO, Context.Ctx](workflow, RegistrationState.Empty, engine)
    val wfInstance = runtime.createInstance("student-123").unsafeRunSync().asInstanceOf[InMemoryWorkflowInstance[IO, Context.Ctx]]

    println("=== Course Registration Workflow ===")
    wfInstance.deliverSignal(Signals.startBrowsing, Signals.BrowsingRequest("student-123", "spring-2024")).unsafeRunSync()
    println(wfInstance.queryState().unsafeRunSync())

    wfInstance
      .deliverSignal(Signals.setPriorities, Signals.PriorityRequest("CR1", List("CS101-Smith", "CS101-Johnson", "CS101-Davis")))
      .unsafeRunSync()
    println(wfInstance.queryState().unsafeRunSync())
    // end_execution

    // start_recovery
    val recoveredInstance = runtime.createInstance("student-123").unsafeRunSync().asInstanceOf[InMemoryWorkflowInstance[IO, Context.Ctx]]
    recoveredInstance.recover(wfInstance.getEvents.unsafeRunSync()).unsafeRunSync()
    assert(wfInstance.queryState().unsafeRunSync() == recoveredInstance.queryState().unsafeRunSync())
    // end_recovery

    wfInstance

  }
  def main(args: Array[String]): Unit = {
    val _ = run // Explicitly discard the return value
    println("Course registration workflow executed and BPMN generated!")
  }
}
