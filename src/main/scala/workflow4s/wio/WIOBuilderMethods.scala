package workflow4s.wio

import cats.effect.IO
import cats.implicits.catsSyntaxEitherId
import workflow4s.wio.WIO.Branch
import workflow4s.wio.internal.WorkflowConversionEvaluator.WorkflowEmbedding
import workflow4s.wio.internal.{EventHandler, QueryHandler, SignalHandler, WorkflowConversionEvaluator}

import scala.annotation.unused
import scala.concurrent.duration.Duration
import scala.reflect.ClassTag

trait WIOBuilderMethods[Ctx <: WorkflowContext] {

  def handleSignal[StIn] = new HandleSignalPartiallyApplied1[StIn]

  class HandleSignalPartiallyApplied1[In] {
    def apply[Sig: ClassTag, Evt <: WCEvent[Ctx] : ClassTag, Resp](@unused signalDef: SignalDef[Sig, Resp])(
      f: (In, Sig) => IO[Evt],
    ): HandleSignalPartiallyApplied2[Sig, In, Evt, Resp] = new HandleSignalPartiallyApplied2[Sig, In, Evt, Resp](SignalHandler(f), signalDef)
  }

  class HandleSignalPartiallyApplied2[Sig: ClassTag, In, Evt <: WCEvent[Ctx] : ClassTag, Resp](
                                                                                         signalHandler: SignalHandler[Sig, Evt, In],
                                                                                         signalDef: SignalDef[Sig, Resp],
                                                                                       ) {

    def handleEvent[Out <: WCState[Ctx]](f: (In, Evt) => Out): HandleSignalPartiallyApplied3[Sig, In, Evt, Resp, Nothing, Out] = {
      new HandleSignalPartiallyApplied3(signalDef, signalHandler, (s: In, e: Evt) => f(s, e).asRight)
    }

    def handleEventWithError[Err, Out <: WCState[Ctx]](
                                                     f: (In, Evt) => Either[Err, Out],
                                                   ): HandleSignalPartiallyApplied3[Sig, In, Evt, Resp, Err, Out] = {
      new HandleSignalPartiallyApplied3(signalDef, signalHandler, f)
    }
  }

  class HandleSignalPartiallyApplied3[Sig: ClassTag, In, Evt <: WCEvent[Ctx] : ClassTag, Resp, Err, Out <: WCState[Ctx]](
                                                                                                                signalDef: SignalDef[Sig, Resp],
                                                                                                                signalHandler: SignalHandler[Sig, Evt, In],
                                                                                                                handleEvent: (In, Evt) => Either[Err, Out],
                                                                                                              ) {
    def produceResponse(f: (In, Evt) => Resp)(implicit errorMeta: ErrorMeta[Err]): WIO[In, Err, Out, Ctx] = {
      val combined = (s: In, e: Evt) => (handleEvent(s, e), f(s, e))
      val eventHandler: EventHandler[In, (Either[Err, Out], Resp), WCEvent[Ctx], Evt] = EventHandler(summon[ClassTag[Evt]].unapply, identity, combined)
      WIO.HandleSignal(signalDef, signalHandler, eventHandler, errorMeta)
    }
  }

  def runIO[State] = new RunIOPartiallyApplied1[State]

  class RunIOPartiallyApplied1[StIn] {
    def apply[Evt <: WCEvent[Ctx] : ClassTag](f: StIn => IO[Evt]): RunIOPartiallyApplied2[StIn, Evt] = {
      new RunIOPartiallyApplied2[StIn, Evt](f)
    }
  }

  class RunIOPartiallyApplied2[In, Evt <: WCEvent[Ctx] : ClassTag](getIO: In => IO[Evt]) {
    def handleEvent[Out <: WCState[Ctx]](f: (In, Evt) => Out): WIO[In, Nothing, Out, Ctx] = {
      WIO.RunIO[Ctx, In, Nothing, Out, Evt](getIO, EventHandler(summon[ClassTag[Evt]].unapply, identity, (s, e: Evt) => f(s, e).asRight), ErrorMeta.noError)
    }

    def handleEventWithError[Err, Out <: WCState[Ctx]](
                                                     f: (In, Evt) => Either[Err, Out],
                                                   )(implicit errorCt: ErrorMeta[Err]): WIO[In, Err, Out, Ctx] = {
      WIO.RunIO[Ctx, In, Err, Out, Evt](getIO, EventHandler(summon[ClassTag[Evt]].unapply, identity, f), errorCt)
    }
  }

  def getState[St <: WCState[Ctx]]: WIO[St, Nothing, St, Ctx] = WIO.Pure(s => s.asRight, ErrorMeta.noError)

  def await[In <: WCState[Ctx]](duration: Duration): WIO[In, Nothing, In, Ctx] = ???

  def pure[St]: PurePartiallyApplied[St] = new PurePartiallyApplied

  class PurePartiallyApplied[In] {
    def apply[O <: WCState[Ctx]](value: O): WIO[In, Nothing, O, Ctx] = WIO.Pure(_ => Right(value), ErrorMeta.noError)

    def make[O <: WCState[Ctx]](f: In => O): WIO[In, Nothing, O, Ctx] = WIO.Pure(s => Right(f(s)), ErrorMeta.noError)

    def makeError[Err, Out >: In <: WCState[Ctx]](f: In => Option[Err])(implicit em: ErrorMeta[Err]): WIO[In, Err, Out, Ctx] = {
      WIO.Pure(s => f(s).map(Left(_)).getOrElse(Right(s: Out)), em)
    }
  }

  //    def unit[In] = pure[In](())

  def raise[In]: RaisePartiallyApplied[In] = new RaisePartiallyApplied

  class RaisePartiallyApplied[In] {
    def apply[Err](value: Err)(implicit ct: ErrorMeta[Err]): WIO[In, Err, Nothing, Ctx] = WIO.Pure(s => Left(value), ct)
  }

  def repeat[Err, Out <: WCState[Ctx]](action: WIO[Out, Err, Out, Ctx]) = RepeatBuilder(action)

  case class RepeatBuilder[Err, Out <: WCState[Ctx]](action: WIO[Out, Err, Out, Ctx]) {
    def untilSome[Out1 <: WCState[Ctx]](f: Out => Option[Out1]): WIO[Out, Err, Out1, Ctx] = WIO.DoWhile(action, f, action)
  }

  def fork[In]: ForkBuilder[In, Nothing, Nothing] = ForkBuilder(Vector())

  // can be removed and replaced with direct instance of WIO.Fork?
  case class ForkBuilder[-In, +Err, +Out <: WCState[Ctx]](branches: Vector[Branch[In, Err, Out, Ctx]]) {
    def branch[T, Err1 >: Err, Out1 >: Out <: WCState[Ctx], In1 <: In](cond: In1 => Option[T])(
      wio: WIO[(In1, T), Err1, Out1, Ctx],
    ): ForkBuilder[In1, Err1, Out1] = addBranch(Branch(cond, wio))

    def addBranch[T, Err1 >: Err, Out1 >: Out <: WCState[Ctx], In1 <: In](
                                                                        b: Branch[In1, Err1, Out1, Ctx],
                                                                      ): ForkBuilder[In1, Err1, Out1] = ForkBuilder(branches.appended(b))

    def done: WIO[In, Err, Out, Ctx] = WIO.Fork(branches)
  }

  def branch[In]: BranchBuilder[In] = BranchBuilder()

  case class BranchBuilder[In]() {
    def when[Err, Out <: WCState[Ctx]](cond: In => Boolean)(wio: WIO[In, Err, Out, Ctx]): Branch[In, Err, Out, Ctx] =
      Branch(cond.andThen(Option.when(_)(())), wio.transformInput((x: (In, Any)) => x._1))

    def create[T, Err, Out <: WCState[Ctx]](cond: In => Option[T])(wio: WIO[(In, T), Err, Out, Ctx]): Branch[In, Err, Out, Ctx] =
      Branch(cond, wio)
  }

  def embed[In, Err, Out <: WCState[Ctx2], Ctx2 <: WorkflowContext, OS[_ <: WCState[Ctx2]] <: WCState[Ctx] ](wio: WIO[In, Err, Out, Ctx2])(embedding: WorkflowEmbedding.Aux[Ctx2, Ctx, OS, In]): WIO[In, Err, OS[Out], Ctx] = {
    WIO.Embedded(wio, embedding)
  }

  def noop(): WIO[Any, Nothing, Nothing, Ctx] = WIO.Noop[Ctx]()

}
