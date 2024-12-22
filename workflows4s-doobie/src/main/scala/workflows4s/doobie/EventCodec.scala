package workflows4s.doobie

import scala.util.Try

trait EventCodec[Event] {

  def read(bytes: IArray[Byte]): Try[Event]
  def write(event: Event): IArray[Byte]

}
