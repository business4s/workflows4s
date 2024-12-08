package workflow4s.runtime.wakeup

import java.time.Instant

import cats.effect.IO

// https://en.wikipedia.org/wiki/Knocker-up
trait KnockerUpper {

  def registerWakeup(at: Instant): IO[Unit]

}

object KnockerUpper {
  val noop: KnockerUpper                     = NoOpKnockerUpper
  val noopFactory: KnockerUpper.Factory[Any] = _ => noop

  type Factory[In] = In => KnockerUpper
}
