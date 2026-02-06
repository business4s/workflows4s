package workflows4s.doobie

import scala.util.Try

/** Serialization codec for workflow events, used by database-backed runtimes to persist events as byte arrays. Typically implemented using Circe JSON
  * or a binary format like Protocol Buffers.
  */
trait ByteCodec[Event] {

  def read(bytes: IArray[Byte]): Try[Event]
  def write(event: Event): IArray[Byte]

}
