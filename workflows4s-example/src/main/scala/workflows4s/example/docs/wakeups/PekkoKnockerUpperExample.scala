package workflows4s.example.docs.wakeups

import scala.annotation.nowarn

@nowarn("msg=unused")
object PekkoKnockerUpperExample {

  // docs_start
  import org.apache.pekko.actor.typed.ActorSystem
  import workflows4s.runtime.pekko.PekkoKnockerUpper

  given ActorSystem[?] = ???
  val knockerUpper     = PekkoKnockerUpper.create
  // docs_end

}
