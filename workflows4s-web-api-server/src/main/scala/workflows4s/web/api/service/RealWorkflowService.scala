package workflows4s.web.api.service

import cats.effect.IO
import com.typesafe.scalalogging.StrictLogging
import io.circe.{Decoder, Encoder, Json}
import sttp.apispec.Schema
import sttp.tapir.docs.apispec.schema.TapirSchemaToJsonSchema
import workflows4s.mermaid.MermaidRenderer
import workflows4s.runtime.WorkflowRuntime
import workflows4s.web.api.model.*
import workflows4s.web.api.service.RealWorkflowService.SignalSchemaProvider.RequestHandler
import workflows4s.web.api.service.RealWorkflowService.WorkflowEntry
import workflows4s.wio.{SignalDef, WorkflowContext}

class RealWorkflowService(
    workflowEntries: List[RealWorkflowService.WorkflowEntry[?]],
) extends WorkflowApiService {

  def listDefinitions(): IO[List[WorkflowDefinition]] =
    IO.pure(workflowEntries.map(e => WorkflowDefinition(e.id, e.name)))

  def getDefinition(id: String): IO[WorkflowDefinition] =
    findEntry(id).map(e => WorkflowDefinition(e.id, e.name))

  def getInstance(definitionId: String, instanceId: String): IO[WorkflowInstance] =
    for {
      entry    <- findEntry(definitionId)
      instance <- getRealInstance(entry, instanceId)
    } yield instance

  override def deliverSignal(request: SignalRequest): IO[Json] = {
    for {
      entry        <- findEntry(request.templateId)
      instance     <- entry.runtime.createInstance(request.instanceId)
      signalHandler = entry.signalSchemaProvider.transformRequest(request.signalId)
      responseE    <- instance.deliverSignal(signalHandler.signalDef, signalHandler.decodeReq(request.signalRequest))
      response     <- IO.fromEither(responseE.left.map(_ => new Exception("Unexpected signal")))
      respJson      = signalHandler.encodeResp(response)
    } yield respJson
  }

  private def findEntry(definitionId: String): IO[RealWorkflowService.WorkflowEntry[?]] =
    IO.fromOption(workflowEntries.find(_.id == definitionId))(new Exception(s"Definition not found: $definitionId"))

  private def getRealInstance[Ctx <: WorkflowContext](
      entry: RealWorkflowService.WorkflowEntry[Ctx],
      instanceId: String,
  ): IO[WorkflowInstance] = {
    for {
      workflowInstance <- entry.runtime.createInstance(instanceId)
      currentState     <- workflowInstance.queryState()
      progress         <- workflowInstance.getProgress
      mermaid           = MermaidRenderer.renderWorkflow(progress)
      signals          <- workflowInstance.getExpectedSignals
    } yield WorkflowInstance(
      id = instanceId,
      templateId = entry.id,
      state = Some(entry.stateEncoder(currentState)),
      mermaidUrl = mermaid.toViewUrl,
      mermaidCode = mermaid.render,
      expectedSignals = signals.map(convertSignal(entry, _)),
    )
  }

  private def convertSignal(entry: WorkflowEntry[?], sig: SignalDef[?, ?]): Signal = {
    Signal(
      id = sig.id,
      name = sig.name,
      requestSchema = entry.signalSchemaProvider.getSchema(sig),
    )
  }

}

object RealWorkflowService {
  case class WorkflowEntry[Ctx <: WorkflowContext](
      id: String,
      name: String,
      runtime: WorkflowRuntime[IO, Ctx],
      stateEncoder: Encoder[workflows4s.wio.WCState[Ctx]],
      signalSchemaProvider: SignalSchemaProvider,
  )

  trait SignalSchemaProvider {
    def getSchema(signalDef: SignalDef[?, ?]): Option[sttp.apispec.Schema]
    def transformRequest(signalId: String): RequestHandler[?, ?]
  }

  object SignalSchemaProvider {
    val NoOp: SignalSchemaProvider = new SignalSchemaProvider {
      override def getSchema(signalDef: SignalDef[?, ?]): Option[Schema]    = None
      override def transformRequest(signalId: String): RequestHandler[?, ?] = ??? // TODO
    }

    val builder = Builder(Map())

    case class RequestHandler[Req, Resp](signalDef: SignalDef[Req, Resp], decodeReq: Json => Req, encodeResp: Resp => Json)

    class Builder(entries: Map[String, Builder.Entry[?, ?]]) extends StrictLogging {
      def add[Req: {sttp.tapir.Schema as s, Decoder as d}, Resp: {Encoder as e}](sigDef: SignalDef[Req, Resp]): Builder = {
        val apiSchema = TapirSchemaToJsonSchema(s, markOptionsAsNullable = true)
        val entry     = Builder.Entry(sigDef, apiSchema, d, e)
        new Builder(entries.updated(sigDef.id, entry))
      }

      def build: SignalSchemaProvider = new SignalSchemaProvider {
        override def getSchema(signalDef: SignalDef[?, ?]): Option[Schema] = {
          val result = entries.get(signalDef.id)
          if result.isEmpty then logger.warn(s"Couldn't find schema for signal ${signalDef}")
          result.map(_.schema)
        }

        override def transformRequest(signalId: String): RequestHandler[?, ?] = {
          entries
            .get(signalId)
            .map(entry => RequestHandler(entry.signalDef, req => entry.reqDecoder.decodeJson(req).toTry.get, resp => entry.respEncoder.apply(resp)))
            .getOrElse(throw new Exception(s"Couldn't find schema for signal ${signalId}"))
        }
      }
    }

    object Builder {
      case class Entry[Req, Resp](
          signalDef: SignalDef[Req, Resp],
          schema: sttp.apispec.Schema,
          reqDecoder: Decoder[Req],
          respEncoder: Encoder[Resp],
      )
    }

  }
}
