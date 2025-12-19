package workflows4s.example.docs

import workflows4s.cats.CatsEffect.given
import workflows4s.example.docs.Context.WIO

object CheckpointExample {

  object checkpoint {
    // start_checkpoint
    val myWorkflow: WIO[Int, Nothing, MyState]            = WIO.pure(MyState(1)).autoNamed
    def saveState(input: Int, state: MyState): MyEvent    = ???
    def recoverState(input: Int, event: MyEvent): MyState = ???

    val checkpointed = myWorkflow.checkpointed(saveState, recoverState)
    // end_checkpoint
  }

  object recovery {
    // start_recovery
    def recoverState(input: Int, event: MyEvent): MyState = ???

    val myWorkflow: WIO[Int, Nothing, MyState] = WIO.recover(recoverState)
    // end_recovery
  }

}
