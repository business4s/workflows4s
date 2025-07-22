package workflows4s.wio

import java.util.UUID

/** Trait representing a signal routing mechanism between external caller and WIO.ForEach element, where signal has to be delivered to a particular
  * subworkflow.
  *
  * @tparam Key
  *   The type of keys passed from the sender to allow for routing.
  * @tparam Elem
  *   The type of elements present on the receiving side (routing target).
  * @tparam In
  *   The input type used when unwrapping signals. Typically state of the workflow.
  */
trait SignalRouter[-Key, Elem, -In] extends SignalRouter.Sender[Key] with SignalRouter.Receiver[Elem, In]

object SignalRouter {

  trait Sender[-Key]        {
    def wrap[InnerReq, Resp](key: Key, req: InnerReq, sigDef: SignalDef[InnerReq, Resp]): SignalRouter.Wrapped[?, Resp]
  }
  trait Receiver[Elem, -In] {

    def outerSignalDef[InnerReq, Resp](innerDef: SignalDef[InnerReq, Resp]): SignalDef[?, Resp]

    def unwrap[OuterReq, InnerReq, Resp](
        signalDef: SignalDef[OuterReq, Resp],
        outerReq: OuterReq,
        input: In,
    ): Option[SignalRouter.Unwrapped[Elem, InnerReq, Resp]]
  }

  case class Wrapped[OuterReq, Resp](sigDef: SignalDef[OuterReq, Resp], req: OuterReq)
  case class Unwrapped[Elem, InnerReq, Resp](elem: Elem, sigDef: SignalDef[InnerReq, Resp], req: InnerReq)

}

class SimpleSignalRouter[Elem] extends BasicSignalRouter[Elem, Elem, Any] {

  override def extractElem(state: Any, key: Elem): Option[Elem] = Some(key)
}

trait BasicSignalRouter[Key, Elem, -Input] extends SignalRouter.Sender[Key] with SignalRouter.Receiver[Elem, Input] {

  def extractElem(state: Input, key: Key): Option[Elem]

  case class WrappedRequest[InnerReq, Resp](key: Key, innerRequest: InnerReq, innerSigDef: SignalDef[InnerReq, Resp])
  val signalDefId = UUID.randomUUID().toString

  def outerSignalDef[InnerReq, Resp](innerDef: SignalDef[InnerReq, Resp]): SignalDef[WrappedRequest[InnerReq, Resp], Resp] = {
    import innerDef.respCt
    SignalDef[WrappedRequest[InnerReq, Resp], Resp](signalDefId, innerDef.name)
  }

  def wrap[InnerReq, Resp](key: Key, req: InnerReq, sigDef: workflows4s.wio.SignalDef[InnerReq, Resp]): SignalRouter.Wrapped[?, Resp] =
    SignalRouter.Wrapped(outerSignalDef(sigDef), WrappedRequest(key, req, sigDef))

  override def unwrap[OuterReq, InnerReq, Resp](
      signalDef: SignalDef[OuterReq, Resp],
      outerReq: OuterReq,
      input: Input,
  ): Option[SignalRouter.Unwrapped[Elem, InnerReq, Resp]] = {
    for {
      _         <- Option.when(signalDef.id == signalDefId)(())
      wrappedReq = outerReq.asInstanceOf[WrappedRequest[InnerReq, Resp]]
      elem      <- extractElem(input, wrappedReq.key)
    } yield SignalRouter.Unwrapped(elem, wrappedReq.innerSigDef, wrappedReq.innerRequest)

  }
}
