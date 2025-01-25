package workflows4s.wio.model
import scala.language.future

import io.circe.{Codec, Decoder, Encoder}

opaque type WIOId <: Seq[Int] = Vector[Int]

object WIOId {
  val root: WIOId = Vector(0)

  extension (id: WIOId) {
    def child(i: Int): WIOId = id :+ i
    def child(): WIOId       = id :+ 0
  }
  given Codec[WIOId] = Codec.from(Decoder[Vector[Int]], Encoder[Vector[Int]]).asInstanceOf[Codec[WIOId]]
}
