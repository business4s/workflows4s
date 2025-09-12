package workflows4s.web.ui.util

import cats.effect.IO

import scala.scalajs.js

object IOFromPromise {

  def apply[A](p: => js.Promise[A]): IO[A] =
    IO.fromFuture(IO(p.toFuture))

}
