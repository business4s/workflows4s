package workflows4s.example.testuitls

import io.circe.Codec
import workflows4s.doobie.ByteCodec

object CirceEventCodec {

  def get[T]()(using Codec[T]): ByteCodec[T] = new ByteCodec[T] {

    import scala.util.{Failure, Success, Try}
    import io.circe.syntax.*
    import java.nio.charset.StandardCharsets.UTF_8

    override def read(bytes: IArray[Byte]): Try[T] = {
      val jsonString = new String(IArray.genericWrapArray(bytes).toArray, UTF_8)
      io.circe.parser.decode[T](jsonString) match {
        case Right(event) => Success(event)
        case Left(error)  => Failure(error)
      }
    }

    override def write(event: T): IArray[Byte] = {
      val jsonString = event.asJson.noSpaces
      IArray.from(jsonString.getBytes(UTF_8))
    }
  }
}
