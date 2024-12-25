package workflows4s.runtime.wakeup.quartz

trait StringCodec[T] {
  def encode(t: T): String
  def decode(s: String): T
}

object StringCodec {
  given StringCodec[String] with {
    override def encode(value: String): String = value
    override def decode(value: String): String = value
  }
}
