package workflows4s.web.ui

import cats.effect.IO
import tyrian.Html.*
import tyrian.*
import sttp.client4.impl.cats.FetchCatsBackend
import sttp.client4.*
import sttp.client4.circe.*
import io.circe.generic.auto.*
import scala.scalajs.js.annotation.*
import sttp.client4.basicRequest
import io.circe.{Decoder, Json}

 
case class WorkflowDefinition(
  id: String,
  name: String
)

case class WorkflowInstance(
  id: String,
  definitionId: String,
  status: InstanceStatus,
  state: Option[Json] = None   
)

enum InstanceStatus {
  case Running, Completed, Failed, Paused
}

case class ApiError(message: String)

 
case class Model(
  workflows: List[WorkflowDefinition] = List.empty,
  loading: Boolean = false,
  error: Option[String] = None,
  selectedWorkflowId: String = "",
  instanceId: String = "",
  currentInstance: Option[WorkflowInstance] = None,
  instanceLoading: Boolean = false,
  instanceError: Option[String] = None,
  showJsonState: Boolean = false
)

 
enum Msg {
  case LoadWorkflows
  case WorkflowsLoaded(result: Either[String, List[WorkflowDefinition]])
  case WorkflowSelected(workflowId: String)
  case InstanceIdChanged(instanceId: String)
  case LoadInstance
  case InstanceLoaded(result: Either[String, WorkflowInstance])
  case NoOp
  case ToggleJsonState
}

@JSExportTopLevel("TyrianApp")
object Main extends TyrianIOApp[Msg, Model] {

  def router: Location => Msg =
    Routing.none(Msg.NoOp)

  def init(flags: Map[String, String]): (Model, Cmd[IO, Msg]) =
    (Model(), Cmd.emit(Msg.LoadWorkflows))

  def update(model: Model): Msg => (Model, Cmd[IO, Msg]) = {
    case Msg.LoadWorkflows =>
      val cmd = loadWorkflowsCmd
      (model.copy(loading = true, error = None), cmd)

    case Msg.WorkflowsLoaded(result) =>
      result match {
        case Right(workflows) =>
          val firstWorkflow = workflows.headOption.map(_.id).getOrElse("")
          (model.copy(
            workflows = workflows, 
            loading = false, 
            selectedWorkflowId = firstWorkflow
          ), Cmd.None)
        case Left(error) =>
          (model.copy(loading = false, error = Some(error)), Cmd.None)
      }

    case Msg.WorkflowSelected(workflowId) =>
      (model.copy(
        selectedWorkflowId = workflowId,
        currentInstance = None,
        instanceError = None
      ), Cmd.None)

    case Msg.InstanceIdChanged(instanceId) =>
      (model.copy(instanceId = instanceId), Cmd.None)

    case Msg.LoadInstance =>
      if (model.selectedWorkflowId.nonEmpty && model.instanceId.nonEmpty) {
        val cmd = loadInstanceCmd(model.selectedWorkflowId, model.instanceId)
        (model.copy(instanceLoading = true, instanceError = None), cmd)
      } else {
        (model.copy(instanceError = Some("Please select a workflow and enter an instance ID")), Cmd.None)
      }

    case Msg.InstanceLoaded(result) =>
      result match {
        case Right(instance) =>
          (model.copy(currentInstance = Some(instance), instanceLoading = false), Cmd.None)
        case Left(error) =>
          (model.copy(instanceError = Some(error), instanceLoading = false), Cmd.None)
      }

    case Msg.NoOp =>
      (model, Cmd.None)

    case Msg.ToggleJsonState =>
      (model.copy(showJsonState = !model.showJsonState), Cmd.None)
  }

  private def loadInstanceCmd(workflowId: String, instanceId: String): Cmd[IO, Msg] = {
    Cmd.Run[IO, Msg] {
      val request = basicRequest
        .get(uri"http://localhost:8081/api/v1/definitions/$workflowId/instances/$instanceId")
        .response(asJson[WorkflowInstance])

      FetchCatsBackend[IO]()
        .send(request)
        .map { response =>
          response.body match {
            case Right(instance) => Msg.InstanceLoaded(Right(instance))
            case Left(error) => Msg.InstanceLoaded(Left(s"Failed to load instance: $error"))
          }
        }
        .handleError(ex => Msg.InstanceLoaded(Left(s"Network error: ${ex.getMessage}")))
    }
  }

  private val loadWorkflowsCmd: Cmd[IO, Msg] = {
    val backend = FetchCatsBackend[IO]()
    
    val request = basicRequest
      .get(uri"http://localhost:8081/api/v1/definitions")
      .response(asJson[List[WorkflowDefinition]])

    val httpCall = backend
      .send(request)
      .map(_.body)
      .map {
        case Right(workflows) => Msg.WorkflowsLoaded(Right(workflows))
        case Left(error) => Msg.WorkflowsLoaded(Left(s"Parse error: ${error.getMessage}"))
      }
      .handleError(error => Msg.WorkflowsLoaded(Left(s"HTTP error: ${error.getMessage}")))

    Cmd.Run(httpCall)
  }

  // Helper function to convert JSON to display map
  private def jsonToDisplayMap(json: Json): Map[String, Any] = {
    json.asObject.map(_.toMap.map { case (k, v) =>
      k -> (v.asString.getOrElse(v.toString))
    }).getOrElse(Map("raw" -> json.toString))
  }

  def view(model: Model): Html[Msg] = {
    div(
      style := """
        min-height: 100vh;
        background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
        padding: 2rem;
        font-family: 'Segoe UI', system-ui, sans-serif;
      """
    )(
      headerView,
      
      div(
        style := """
          display: grid;
          grid-template-columns: 1fr 1fr;
          gap: 2rem;
          max-width: 1400px;
          margin: 0 auto;
        """
      )(
        workflowsSection(model),
        instanceViewerSection(model)
      )
    )
  }

  private def headerView: Html[Msg] = {
    div(
      style := """
        text-align: center;
        margin-bottom: 3rem;
        color: white;
      """
    )(
      h1(
        style := """
          font-size: 3rem;
          margin-bottom: 1rem;
          font-weight: 300;
          text-shadow: 0 2px 4px rgba(0,0,0,0.3);
        """
      )("workflows4s"),
      p(
        style := """
          font-size: 1.2rem;
          opacity: 0.9;
          font-weight: 300;
        """
      )("🚀 Real Workflow Management Dashboard")
    )
  }

  private def workflowsSection(model: Model): Html[Msg] = {
    div(
      style := """
        background: rgba(255, 255, 255, 0.95);
        backdrop-filter: blur(10px);
        border-radius: 20px;
        padding: 2rem;
        box-shadow: 0 8px 32px rgba(0, 0, 0, 0.1);
      """
    )(
      if (model.loading) loadingView
      else model.error.fold(workflowsView(model.workflows))(errorView)
    )
  }

  private def instanceViewerSection(model: Model): Html[Msg] = {
    div(
      style := """
        background: rgba(255, 255, 255, 0.95);
        backdrop-filter: blur(10px);
        border-radius: 20px;
        padding: 2rem;
        box-shadow: 0 8px 32px rgba(0, 0, 0, 0.1);
      """
    )(
      h2(
        style := """
          color: #333;
          margin-bottom: 2rem;
          font-size: 2rem;
        """
      )("🔍 Instance Viewer"),
      
      // Workflow selector
      div(
        style := "margin-bottom: 1.5rem;"
      )(
        label(
          style := "display: block; margin-bottom: 0.5rem; font-weight: 600; color: #555;",
        )("Select Workflow:"),
        select(
          style := """
            width: 100%;
            padding: 0.75rem;
            border-radius: 8px;
            border: 2px solid #e1e5e9;
            font-size: 1rem;
          """,
          onChange(value => Msg.WorkflowSelected(value))
        )(
          model.workflows.map { workflow =>
            option(
              value := workflow.id,
              selected := (workflow.id == model.selectedWorkflowId)
            )(workflow.name)
          }
        )
      ),
      
      // Instance ID input
      div(
        style := "margin-bottom: 1.5rem;"
      )(
        label(
          style := "display: block; margin-bottom: 0.5rem; font-weight: 600; color: #555;",
        )("Instance ID:"),
        input(
          style := """
            width: 100%;
            padding: 0.75rem;
            border-radius: 8px;
            border: 2px solid #e1e5e9;
            font-size: 1rem;
          """,
          placeholder := "Enter instance ID (e.g., inst-1)",
          value := model.instanceId,
          onInput(value => Msg.InstanceIdChanged(value))
        )
      ),
      
      // Load button
      button(
        style := """
          background: linear-gradient(45deg, #4CAF50, #45a049);
          color: white;
          border: none;
          padding: 0.75rem 2rem;
          border-radius: 50px;
          font-size: 1rem;
          font-weight: 600;
          cursor: pointer;
          margin-bottom: 2rem;
          transition: all 0.3s ease;
        """,
        onClick(Msg.LoadInstance),
        disabled(model.instanceLoading)
      )(if (model.instanceLoading) "Loading..." else "Load Instance"),
      
      // Instance details
      instanceDetailsView(model)
    )
  }

 private def instanceDetailsView(model: Model): Html[Msg] = {
  model.instanceError match {
    case Some(error) => 
      div(
        style := """
          background: #fee;
          border: 1px solid #fcc;
          border-radius: 8px;
          padding: 1rem;
          color: #c33;
        """
      )(s"Error: $error")
      
    case None =>
      model.currentInstance match {
        case Some(instance) =>
          div()(
            // Instance details card
            div(
              style := """
                background: #f8f9fa;
                border-radius: 12px;
                padding: 1.5rem;
                border: 1px solid #e9ecef;
                margin-bottom: 1.5rem;
              """
            )(
              h3(
                style := "margin-top: 0; color: #495057; margin-bottom: 1rem;"
              )(s"Instance: ${instance.id}"),
              
              div(
                style := "display: grid; gap: 0.75rem;"
              )(
                instanceField("Status", statusBadge(instance.status)),
                instanceField("Definition", span(instance.definitionId)),
              )
            ),
            
            // JSON State Viewer
            jsonStateViewer(model, instance)
          )
          
        case None =>
          div(
            style := """
              text-align: center;
              color: #6c757d;
              font-style: italic;
              padding: 2rem;
            """
          )("Select a workflow and enter an instance ID to view details")
      }
  }
}

  private def jsonStateViewer(model: Model, instance: WorkflowInstance): Html[Msg] = {
    div(
      style := """
        background: #f8f9fa;
        border-radius: 12px;
        border: 1px solid #e9ecef;
        overflow: hidden;
      """
    )(
      // Header with toggle button
      div(
        style := """
          background: #e9ecef;
          padding: 1rem 1.5rem;
          display: flex;
          justify-content: space-between;
          align-items: center;
          cursor: pointer;
          transition: background 0.2s ease;
        """,
        onClick(Msg.ToggleJsonState)
      )(
        h4(
          style := "margin: 0; color: #495057; font-size: 1.1rem;"
        )("🔍 Instance State (JSON)"),
        
        span(
          style := """
            font-size: 1.2rem;
            color: #6c757d;
            transition: transform 0.2s ease;
            transform: """ + (if (model.showJsonState) "rotate(180deg)" else "rotate(0deg)") + """;
          """
        )("▼")
      ),
      
      // JSON content (collapsible)
      if (model.showJsonState) {
        div(
          style := """
            padding: 1.5rem;
            background: #fff;
          """
        )(
          jsonContent(instance)
        )
      } else {
        div()
      }
    )
  }

  // Single jsonContent method with proper return type
   private def jsonContent(instance: WorkflowInstance): Html[Msg] = {
  val displayState = instance.state match {
    case Some(json) => jsonToDisplayMap(json)
    case None => Map(
      "id" -> instance.id,
      "definitionId" -> instance.definitionId,
      "status" -> instance.status.toString,
      "state" -> Map(
        "example" -> "This would contain the actual workflow state",
        "variables" -> Map(
          "userId" -> "user-123",
          "amount" -> 1000.50,
          "approved" -> true
        ),
        "history" -> List(
          Map("step" -> "initialize", "timestamp" -> "2024-01-15T10:00:00Z"),
          Map("step" -> "validate", "timestamp" -> "2024-01-15T10:01:30Z"),
          Map("step" -> "pending-approval", "timestamp" -> "2024-01-15T10:02:15Z")
        )
      )
    )
  }
  
  div()(
    // JSON viewer with syntax highlighting
    pre(
      style := """
        background: #f8f9fa;
        border: 1px solid #dee2e6;
        border-radius: 8px;
        padding: 1rem;
        margin: 0;
        overflow-x: auto;
        font-family: 'Monaco', 'Menlo', 'Ubuntu Mono', monospace;
        font-size: 0.875rem;
        line-height: 1.4;
        color: #495057;
        max-height: 400px;
        overflow-y: auto;
      """
    )(
      code(
        style := "color: #495057;"
      )(formatJson(displayState))
    ),
    
    // Copy button
    div(
      style := "margin-top: 1rem; text-align: right;"
    )(
      button(
        style := """
          background: #007bff;
          color: white;
          border: none;
          padding: 0.5rem 1rem;
          border-radius: 6px;
          font-size: 0.875rem;
          cursor: pointer;
          transition: background 0.2s ease;
        """,
        onClick(Msg.NoOp)
      )("📋 Copy JSON")
    )
  )
}
  private def formatJson(data: Map[String, Any], indent: Int = 0): String = {
    def formatValue(value: Any): String = value match {
      case s: String => s""""$s""""
      case n: Number => n.toString
      case b: Boolean => b.toString
      case null => "null"
      case m: Map[_, _] => formatMap(m.asInstanceOf[Map[String, Any]], indent + 1)
      case l: List[_] => formatList(l, indent + 1)
      case other => s""""$other""""
    }
    
    def formatMap(map: Map[String, Any], mapIndent: Int): String = {
      if (map.isEmpty) "{}"
      else {
        val mapIndentStr = "  " * mapIndent
        val mapNextIndentStr = "  " * (mapIndent + 1)
        val entries = map.map { case (key, value) =>
          s"""$mapNextIndentStr"$key": ${formatValue(value)}"""
        }.mkString(",\n")
        s"{\n$entries\n$mapIndentStr}"
      }
    }
    
    def formatList(list: List[Any], listIndent: Int): String = {
      if (list.isEmpty) "[]"
      else {
        val listIndentStr = "  " * listIndent
        val listNextIndentStr = "  " * (listIndent + 1)
        val entries = list.map(value => s"$listNextIndentStr${formatValue(value)}").mkString(",\n")
        s"[\n$entries\n$listIndentStr]"
      }
    }
    
    formatMap(data, indent)
  }

  private def instanceField(label: String, value: Html[Msg]): Html[Msg] = {
    div(
      style := "display: flex; justify-content: space-between; align-items: center;"
    )(
      span(
        style := "font-weight: 600; color: #495057;"
      )(s"$label:"),
      value
    )
  }

  private def statusBadge(status: InstanceStatus): Html[Msg] = {
    val (bgColor, textColor) = status match {
      case InstanceStatus.Running => ("#28a745", "white")
      case InstanceStatus.Completed => ("#007bff", "white") 
      case InstanceStatus.Failed => ("#dc3545", "white")
      case InstanceStatus.Paused => ("#ffc107", "black")
    }
    
    span(
      style := s"""
        background: $bgColor;
        color: $textColor;
        padding: 0.25rem 0.75rem;
        border-radius: 20px;
        font-size: 0.875rem;
        font-weight: 600;
      """
    )(status.toString)
  }

  

  private val loadingView: Html[Msg] = {
    div(
      style := """
        text-align: center;
        padding: 3rem;
        color: #666;
      """,
    )(
      div(
        style := """
          font-size: 2rem;
          margin-bottom: 1rem;
        """,
      )("⏳"),
      div("Loading workflows...")
    )
  }

  private def errorView(error: String): Html[Msg] = {
    div(
      style := """
        text-align: center;
        padding: 3rem;
        color: #e74c3c;
      """,
    )(
      div(
        style := """
          font-size: 2rem;
          margin-bottom: 1rem;
        """,
      )("❌"),
      div(s"Error: $error"),
      div(
        style := "margin-top: 1rem; font-size: 0.9rem; color: #666;",
      )("Make sure your API server is running on http://localhost:8081")
    )
  }

  private def workflowsView(workflows: List[WorkflowDefinition]): Html[Msg] = {
    div()(
      h2(
        style := """
          color: #333;
          margin-bottom: 2rem;
          font-size: 2rem;
        """,
      )(s"📋 Workflows (${workflows.length})"),
      
      if (workflows.isEmpty) {
        div(
          style := """
            text-align: center;
            padding: 3rem;
            color: #666;
          """,
        )(
          div("No workflows found"),
          div(
            style := "margin-top: 1rem; font-size: 0.9rem;",
          )("Check that your API server is running with real workflows")
        )
      } else {
        div(
          style := """
            display: grid;
            grid-template-columns: repeat(auto-fit, minmax(400px, 1fr));
            gap: 2rem;
          """,
        )(
          workflows.map(workflowCard)*
        )
      }
    )
  }

 private def workflowCard(workflow: WorkflowDefinition): Html[Msg] = {
  div(
    style := """
      background: white;
      border-radius: 15px;
      padding: 2rem;
      box-shadow: 0 10px 25px rgba(0, 0, 0, 0.1);
      border-left: 5px solid #4ecdc4;
      transition: all 0.3s ease;
    """,
  )(
    div(
      style := """
        display: flex;
        align-items: center;
        margin-bottom: 1rem;
      """,
    )(
      div(
        style := """
          font-size: 2rem;
          margin-right: 1rem;
        """,
      )(workflowIcon(workflow.id)),
      h3(
        style := """
          color: #333;
          margin: 0;
          font-size: 1.4rem;
        """,
      )(workflow.name)
    ),
    
 
    div(
      style := """
        color: #666;
        margin-bottom: 1rem;
        line-height: 1.5;
      """,
    )(s"Workflow ID: ${workflow.id}"),
    
    div(
      style := """
        color: #888;
        font-size: 0.9rem;
      """,
    )("Ready to process instances")
  )
}

  private def workflowIcon(workflowId: String): String = {
    workflowId match {
      case id if id.contains("course") => "🎓"
      case id if id.contains("pull-request") => "🔀"
      case _ => "⚙️"
    }
  }

  def subscriptions(model: Model): Sub[IO, Msg] =
    Sub.None
}