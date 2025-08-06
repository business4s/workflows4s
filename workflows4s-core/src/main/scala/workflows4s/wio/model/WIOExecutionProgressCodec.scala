package workflows4s.wio.model

import io.circe.{Codec, Decoder, Encoder, Json}

object WIOExecutionProgressCodec {

  // Simple encoder that converts progress to string representation
  given Encoder[WIOExecutionProgress[String]] = Encoder.encodeString.contramap(_.toString)
  
  // Decoder that throws for now since we only need encoding for the API
  given Decoder[WIOExecutionProgress[String]] = Decoder.decodeString.map(_ => 
    throw new NotImplementedError("Decoding WIOExecutionProgress not implemented yet")
  )

  // Alternative: If you want to encode as JSON object
  given Codec[WIOExecutionProgress[String]] = Codec.from(
    Decoder[Json].map(_ => throw new NotImplementedError("Decoding not implemented yet")),
    Encoder[Json].contramap(_ => Json.obj("_type" -> Json.fromString("WIOExecutionProgress")))
  )
}