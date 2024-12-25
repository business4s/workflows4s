package workflows4s.example.pekko

import scala.reflect.ClassTag

import io.circe.Codec
import io.circe.syntax.EncoderOps
import org.apache.pekko.serialization.Serializer

abstract class PekkoCirceSerializer[T <: AnyRef](using ct: ClassTag[T], c: Codec[T]) extends Serializer {
  override def includeManifest: Boolean = true

  override def toBinary(obj: AnyRef): Array[Byte]                     = {
    obj match {
      case ct(e) => e.asJson.noSpaces.getBytes
      case other => ???
    }
  }
  def fromBinary(bytes: Array[Byte], clazz: Option[Class[?]]): AnyRef = {
    io.circe.parser.decode[T](String(bytes)).toTry.get
  }
}
