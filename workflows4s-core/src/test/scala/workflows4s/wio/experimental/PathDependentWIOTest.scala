package workflows4s.wio.experimental

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class PathDependentWIOTest extends AnyFreeSpec with Matchers {

  "PathDependentWIO" - {
    "should support basic types" in {
      object MyWorkflow extends Workflow {
        type Event = String
        type State = Int
      }

      val step1: MyWorkflow.WIO[Any, Nothing, Int]    = MyWorkflow.WIO.Pure(42)
      val step2: MyWorkflow.WIO[Int, Nothing, String] = step1.map(_.toString)
      println(step2)

      // Just verifying that it compiles and types are correct
      succeed
    }
  }

}
