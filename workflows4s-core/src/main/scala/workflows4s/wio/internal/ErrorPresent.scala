package workflows4s.wio.internal

import scala.annotation.implicitNotFound
import scala.util.NotGiven

@implicitNotFound(
  "The error type ${Err} was inferred to be Nothing, which means no error can occur. Operation you're trying to perform makes sense only if an error is possible.",
)
trait ErrorPresent[Err]

object ErrorPresent {

  given instance[Err](using NotGiven[Err =:= Nothing]): ErrorPresent[Err] = null
}
