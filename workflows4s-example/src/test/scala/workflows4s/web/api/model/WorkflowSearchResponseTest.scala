package workflows4s.web.api.model

import io.circe.syntax.EncoderOps
import org.scalatest.freespec.AnyFreeSpec

class WorkflowSearchResponseTest extends AnyFreeSpec {

  "encode decode round trip" - {

    "empty list" in {

      val initial = WorkflowSearchResponse(List(), 0)
      val encoded = initial.asJson.noSpaces
      val decoded = io.circe.parser.decode[WorkflowSearchResponse](encoded)

      assert(decoded == Right(initial))

    }
  }

}
