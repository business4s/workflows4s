package workflow4s

package object wio {

  type WIOT[-In, +Err, +Out] = WorkflowContext#WIO[In, Err, Out]

  object WIOT {
    type Total[In] = WIOT[In, Any, Any]
    type States[In, Out] = WIOT[In, Any, Out]
  }

}
