package workflows4s.wio.builders

import cats.effect.IO
import cats.implicits.catsSyntaxEitherId
import workflows4s.wio.*
import workflows4s.wio.ErrorMeta.NoError
import workflows4s.wio.internal.{EventHandler, WorkflowEmbedding}

import scala.reflect.ClassTag

trait WIOBuilderMethods[Ctx <: WorkflowContext] {

  def end: WIO[IO, Any, Nothing, Nothing, Ctx] = WIO.End[IO, Ctx]()

  def getState[St <: WCState[Ctx]]: WIO[IO, St, Nothing, St, Ctx] = WIO.Pure(s => s.asRight, WIO.Pure.Meta(NoError(), None))

  def embed[In, Err, Out <: WCState[InnerCtx], InnerCtx <: WorkflowContext, OS[_ <: WCState[InnerCtx]] <: WCState[Ctx]](
      wio: WIO[IO, In, Err, Out, InnerCtx],
  )(
      embedding: WorkflowEmbedding.Aux[InnerCtx, Ctx, OS, In],
  ): WIO[IO, In, Err, OS[Out], Ctx] = {
    WIO.Embedded(wio, embedding)
  }

  def noop(): WIO[IO, Any, Nothing, Nothing, Ctx] = WIO.End[IO, Ctx]()

  def recover[In, Evt <: WCEvent[Ctx], Out <: WCState[Ctx]](
      handleEvent: (In, Evt) => Out,
  )(using evtCt: ClassTag[Evt]): WIO[IO, In, Nothing, Out, Ctx] = {
    WIO.Recovery(
      EventHandler.partial[WCEvent[Ctx], In, Out, Evt](identity, handleEvent),
    )
  }

}
