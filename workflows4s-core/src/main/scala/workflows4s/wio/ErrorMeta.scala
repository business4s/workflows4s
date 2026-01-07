package workflows4s.wio

import scala.reflect.ClassTag

import workflows4s.wio.model.ModelUtils

sealed trait ErrorMeta[T] {
  def nameOpt: Option[String]
  def canFail: Boolean = this match {
    case ErrorMeta.NoError() => false
    case ErrorMeta.Present(name) => true
  }
}

object ErrorMeta {
  case class NoError[T]() extends ErrorMeta[T] {
    override def nameOpt: Option[String] = None
  }

//  case class NoInfo[T]() extends ErrorMeta[T] {
//    override def nameOpt: Option[String] = Some("???")
//  }

  case class Present[T](name: String) extends ErrorMeta[T] {
    override def nameOpt: Option[String] = Some(name)
  }

  given noError: ErrorMeta[Nothing] = NoError[Nothing]()

  given fromClassTag[T](using ct: ClassTag[T]): ErrorMeta[T] = Present(ModelUtils.getPrettyNameForClass(ct))

}
