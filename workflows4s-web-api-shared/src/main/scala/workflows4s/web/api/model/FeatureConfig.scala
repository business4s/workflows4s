package workflows4s.web.api.model

import io.circe.Codec
import sttp.tapir.Schema

case class FeatureConfig(searchEnabled: Boolean = false) derives Codec, Schema
