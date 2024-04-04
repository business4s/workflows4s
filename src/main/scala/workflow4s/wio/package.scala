package workflow4s

package object wio {

  type WIOT[-In, +Err, +Out] = WorkflowContext#WIO[In, Err, Out]

  object WIOT {
    type Total[In] = WIOT[In, Nothing, Any]
    type States[In, Out] = WIOT[In, Any, Out]

    type TotalT[Ctx <: WorkflowContext] = WIOT[Ctx#State, Nothing, Ctx#State]
    type StatesT[Ctx <: WorkflowContext, In] = Ctx#WIO[In, Nothing, Ctx#State]

  }

}
