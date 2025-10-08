package workflows4s.web.api.server

import cats.MonadError
import cats.syntax.all.*
import io.circe.Json
import workflows4s.mermaid.MermaidRenderer
import workflows4s.runtime.registry.{WorkflowRegistry, WorkflowSearch}
import workflows4s.web.api.model.*
import workflows4s.wio.{SignalDef, WorkflowContext}

class WorkflowApiServiceImpl[F[_]](
    workflowEntries: List[WorkflowEntry[F, ?]],
    workflowSearch: Option[WorkflowSearch[F]],
)(using me: MonadError[F, Throwable])
    extends WorkflowApiService[F] {

  def listDefinitions(): F[List[WorkflowDefinition]] =
    workflowEntries.map(convertEntry).pure[F]

  def getDefinition(id: String): F[WorkflowDefinition] =
    findEntry(id).map(convertEntry)

  private def convertEntry(e: WorkflowEntry[F, ?]): WorkflowDefinition = {
    val mermaidDiagram = MermaidRenderer.renderWorkflow(e.runtime.workflow.toProgress, true)
    WorkflowDefinition(e.id, e.name, e.description, mermaidDiagram.toViewUrl, mermaidDiagram.render)
  }

  def getInstance(templateId: String, instanceId: String): F[WorkflowInstance] =
    for {
      entry    <- findEntry(templateId)
      instance <- getInstanceFromEntry(entry, instanceId)
    } yield instance

  override def deliverSignal(request: SignalRequest): F[Json] = {
    for {
      entry        <- findEntry(request.templateId)
      instance     <- entry.runtime.createInstance(request.instanceId)
      signalHandler = entry.signalSupport.getCodec(request.signalId)
      responseE    <- instance.deliverSignal(signalHandler.signalDef, signalHandler.decodeReq(request.signalRequest))
      response     <- me.fromEither(responseE.left.map(_ => new Exception("Unexpected signal")))
      respJson      = signalHandler.encodeResp(response)
    } yield respJson
  }

  private def findEntry(templateId: String): F[WorkflowEntry[F, ?]] =
    me.fromOption(workflowEntries.find(_.id == templateId), new Exception(s"Definition not found: $templateId"))

  private def getInstanceFromEntry[Ctx <: WorkflowContext](
      entry: WorkflowEntry[F, Ctx],
      instanceId: String,
  ): F[WorkflowInstance] = {
    for {
      workflowInstance <- entry.runtime.createInstance(instanceId)
      currentState     <- workflowInstance.queryState()
      progress         <- workflowInstance.getProgress
      mermaid           = MermaidRenderer.renderWorkflow(progress)
      signals          <- workflowInstance.getExpectedSignals
    } yield WorkflowInstance(
      id = instanceId,
      templateId = entry.id,
      state = entry.stateEncoder(currentState),
      mermaidUrl = mermaid.toViewUrl,
      mermaidCode = mermaid.render,
      expectedSignals = signals.map(convertSignal(entry, _)),
    )
  }

  private def convertSignal(entry: WorkflowEntry[F, ?], sig: SignalDef[?, ?]): Signal = {
    Signal(
      id = sig.id,
      name = sig.name,
      requestSchema = entry.signalSupport.getSchema(sig),
    )
  }

  override def searchWorkflows(query: WorkflowSearchRequest): F[List[WorkflowSearchResult]] = {
    workflowSearch match {
      case Some(search) =>
        val domainQuery = WorkflowSearch.Query(
          status = query.status.map({
            case ExecutionStatus.Running  => WorkflowRegistry.ExecutionStatus.Running
            case ExecutionStatus.Awaiting => WorkflowRegistry.ExecutionStatus.Awaiting
            case ExecutionStatus.Finished => WorkflowRegistry.ExecutionStatus.Finished
          }),
          createdAfter = query.createdAfter,
          createdBefore = query.createdBefore,
          updatedAfter = query.updatedAfter,
          updatedBefore = query.updatedBefore,
          wakeupBefore = query.wakeupBefore,
          wakeupAfter = query.wakeupAfter,
          tagFilters = List(), // not supported yet
          sort = query.sort.map {
            case WorkflowSearchRequest.SortBy.CreatedAsc  => WorkflowSearch.SortBy.CreatedAsc
            case WorkflowSearchRequest.SortBy.CreatedDesc => WorkflowSearch.SortBy.CreatedDesc
            case WorkflowSearchRequest.SortBy.UpdatedAsc  => WorkflowSearch.SortBy.UpdatedAsc
            case WorkflowSearchRequest.SortBy.UpdatedDesc => WorkflowSearch.SortBy.UpdatedDesc
            case WorkflowSearchRequest.SortBy.WakeupAsc   => WorkflowSearch.SortBy.WakeupAsc
            case WorkflowSearchRequest.SortBy.WakeupDesc  => WorkflowSearch.SortBy.WakeupDesc
          },
          limit = query.limit,
          offset = query.offset,
        )

        search
          .search(query.templateId, domainQuery)
          .map(
            _.map(r =>
              WorkflowSearchResult(
                r.id.templateId,
                r.id.instanceId,
                r.status match {
                  case WorkflowRegistry.ExecutionStatus.Running  => ExecutionStatus.Running
                  case WorkflowRegistry.ExecutionStatus.Awaiting => ExecutionStatus.Awaiting
                  case WorkflowRegistry.ExecutionStatus.Finished => ExecutionStatus.Finished
                },
                r.createdAt,
                r.updatedAt,
                r.wakeupAt,
              ),
            ),
          )
      case None         =>
        me.raiseError(new Exception("Search not configured")) // in the future we will expose this information and UI will handle it gracefully
    }

  }
}
