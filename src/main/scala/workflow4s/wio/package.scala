package workflow4s

package object wio {

  type WIOT[+Err, +Out, -StIn, +StOut] = WorkflowContext#WIO[Err, Out, StIn, StOut]

  object WIOT {
    type Total[St] = WIOT[Any, Any, St, Any]
    type States[StIn, StOut] = WIOT[Any, Any, StIn, StOut]
  }

}
