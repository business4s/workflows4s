package workflows4s.web.api.model

import io.circe.{Codec, Decoder, Encoder}
import sttp.model.Uri
import sttp.tapir.Schema

given Encoder[Uri] = summon[Encoder[String]].contramap(_.toString)
given Decoder[Uri] = summon[Decoder[String]].emap(Uri.parse)

// This structure is expected to be returned by the UI server as a static asset
case class UIConfig(apiUrl: Uri, enableTestUtils: Boolean = false) derives Codec, Schema

