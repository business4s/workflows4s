package workflow4s.wio

import scala.reflect.ClassTag

sealed trait ErrorMeta[T] {
  def nameOpt: Option[String]
}

object ErrorMeta {
  case class NoError[T]() extends ErrorMeta[T] {
    override def nameOpt: Option[String] = None
  }

//  case class NoInfo[T]() extends ErrorMeta[T] {
//    override def nameOpt: Option[String] = Some("???")
//  }

  case class Present[T](name: String) extends ErrorMeta[T]{
    override def nameOpt: Option[String] = Some(name)
  }

  implicit def noError: ErrorMeta[Nothing] = NoError[Nothing]()

  implicit def fromClassTag[T](implicit ct: ClassTag[T]): ErrorMeta[T] = Present(ct.runtimeClass.getSimpleName)

}
