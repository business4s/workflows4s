package workflow4s.wio

import cats.effect.IO

import java.time.Instant

// https://en.wikipedia.org/wiki/Knocker-up
trait KnockerUpper {

  def registerWakeup(at: Instant): IO[Unit]

}

object KnockerUpper {
  val noop: KnockerUpper = _ => IO.unit

  type Factory[In] = In => KnockerUpper
}
