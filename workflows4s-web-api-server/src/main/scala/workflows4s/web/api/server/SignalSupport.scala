package workflows4s.web.api.server

import com.typesafe.scalalogging.StrictLogging
import io.circe.{Decoder, Encoder, Json}
import sttp.apispec.Schema
import sttp.tapir.docs.apispec.schema.TapirSchemaToJsonSchema
import workflows4s.web.api.server.SignalSupport.SignalCodec
import workflows4s.wio.SignalDef

/** Allows api to support retriving expected signals and sending them
  */
trait SignalSupport {
  def getRequestSchema(signalDef: SignalDef[?, ?]): Option[sttp.apispec.Schema]
  def getCodec(signalId: String): SignalCodec[?, ?]
  def getRegisteredSignalIds: Set[String]
}

object SignalSupport {
  val NoSupport: SignalSupport = new SignalSupport {
    override def getRequestSchema(signalDef: SignalDef[?, ?]): Option[Schema] = None
    override def getCodec(signalId: String): SignalCodec[?, ?]                = throw new Exception(
      "transformRequest executed in NoSupport SignalSupport. This should never happen.",
    )
    override def getRegisteredSignalIds: Set[String]                          = Set.empty
  }

  val builder = Builder(Map())

  case class SignalCodec[Req, Resp](signalDef: SignalDef[Req, Resp], decodeReq: Json => Req, encodeResp: Resp => Json)

  class Builder(entries: Map[String, Builder.Entry[?, ?]]) extends StrictLogging {
    def add[Req: {sttp.tapir.Schema as s, Decoder as d}, Resp: {Encoder as e}](sigDef: SignalDef[Req, Resp]): Builder = {
      val apiSchema = TapirSchemaToJsonSchema(s, markOptionsAsNullable = true)
      val entry     = Builder.Entry(sigDef, apiSchema, d, e)
      new Builder(entries.updated(sigDef.id, entry))
    }

    def build: SignalSupport = new SignalSupport {
      private val registeredIds = entries.keySet

      override def getRequestSchema(signalDef: SignalDef[?, ?]): Option[Schema] = {
        val result = entries.get(signalDef.id)
        if result.isEmpty then logger.warn(s"Couldn't find schema for signal ${signalDef}")
        result.map(_.reqSchema)
      }

      override def getCodec(signalId: String): SignalCodec[?, ?] = {
        entries
          .get(signalId)
          .map(entry => SignalCodec(entry.signalDef, req => entry.reqDecoder.decodeJson(req).toTry.get, resp => entry.respEncoder.apply(resp)))
          .getOrElse(throw new Exception(s"Couldn't find schema for signal ${signalId}"))
      }

      override def getRegisteredSignalIds: Set[String] = registeredIds
    }
  }

  object Builder {
    case class Entry[Req, Resp](
        signalDef: SignalDef[Req, Resp],
        reqSchema: sttp.apispec.Schema,
        reqDecoder: Decoder[Req],
        respEncoder: Encoder[Resp],
    )
  }

}
