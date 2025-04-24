package workflows4s.wio.builders

import cats.implicits.catsSyntaxEitherId
import workflows4s.wio.*
import workflows4s.wio.ErrorMeta.NoError
import workflows4s.wio.internal.{EventHandler, WorkflowEmbedding}

import scala.reflect.ClassTag

trait WIOBuilderMethods[Ctx <: WorkflowContext] {

  def end: WIO[Any, Nothing, Nothing, Ctx] = WIO.End[Ctx]()

  def getState[St <: WCState[Ctx]]: WIO[St, Nothing, St, Ctx] = WIO.Pure(s => s.asRight, WIO.Pure.Meta(NoError(), None))

  def embed[In, Err, Out <: WCState[InnerCtx], InnerCtx <: WorkflowContext, OS[_ <: WCState[InnerCtx]] <: WCState[Ctx]](
      wio: WIO[In, Err, Out, InnerCtx],
  )(
      embedding: WorkflowEmbedding.Aux[InnerCtx, Ctx, OS, In],
  ): WIO[In, Err, OS[Out], Ctx] = {
    WIO.Embedded(wio, embedding)
  }

  def noop(): WIO[Any, Nothing, Nothing, Ctx] = WIO.End[Ctx]()

  def recover[In, Evt <: WCEvent[Ctx], Out <: WCState[Ctx]](handleEvent: (In, Evt) => Out)(using evtCt: ClassTag[Evt]): WIO[In, Nothing, Out, Ctx] = {
    WIO.Recovery(
      EventHandler[WCEvent[Ctx], In, Out, Evt](evtCt.unapply, identity, handleEvent),
    )
  }

}
