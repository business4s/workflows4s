package workflows4s.wio.builders

import cats.implicits.catsSyntaxEitherId
import workflows4s.wio.*
import workflows4s.wio.ErrorMeta.NoError
import workflows4s.wio.internal.WorkflowEmbedding

import scala.reflect.ClassTag

trait WIOBuilderMethods[Ctx <: WorkflowContext] {

  def end: WIO[Any, Nothing, Nothing, Ctx] = WIO.End[Ctx]()

  def getState[St <: WCState[Ctx]]: WIO[St, Nothing, St, Ctx] = WIO.Pure(s => s.asRight, WIO.Pure.Meta(NoError(), None))

  def embed[In, Err, Out <: WCState[InnerCtx], InnerCtx <: WorkflowContext, OS[_ <: WCState[InnerCtx]] <: WCState[Ctx]](
      wio: WIO[In, Err, Out, InnerCtx],
  )(
      embedding: WorkflowEmbedding.Aux[InnerCtx, Ctx, OS, In],
      initialState: In => WCState[InnerCtx],
  ): WIO[In, Err, OS[Out], Ctx] = {
    WIO.Embedded(wio, embedding, initialState)
  }

  def noop(): WIO[Any, Nothing, Nothing, Ctx] = WIO.End[Ctx]()

  def recover[In, Evt <: WCEvent[Ctx], Out <: WCState[Ctx]](handleEvent: (In, Evt) => Out)(using ClassTag[Evt]): WIO[In, Nothing, Out, Ctx] = {
    noop().checkpointed[Evt, In, Out](
      (_, _) => throw new Exception("We tried to generate an event from recovery block. This should never happen"),
      handleEvent,
    )
  }

}
