package workflow4s.wio

import cats.effect.IO
import cats.implicits.{catsSyntaxEitherId, catsSyntaxOptionId}
import workflow4s.wio.internal.WorkflowConversionEvaluator.WorkflowEmbedding
import workflow4s.wio.internal.{EventHandler, QueryHandler, SignalHandler, WorkflowConversionEvaluator}
import workflow4s.wio.model.ModelUtils

import scala.annotation.{targetName, unused}
import scala.concurrent.duration.Duration
import scala.language.implicitConversions
import scala.reflect.ClassTag

trait WorkflowContext extends WIOMethodsParent { parentCtx =>
  type Event
  type State
  type F[T] // todo, rename not IO

  sealed trait WIO[-In, +Err, +Out] extends WIOMethods[In, Err, Out] {
    val context: parentCtx.type              = parentCtx
    val asParents: context.WIO[In, Err, Out] = this.asInstanceOf // TODO
  }

  object WIO {

    type Total[In]       = WIO[In, Nothing, Any]
    type States[In, Out] = WIO[In, Any, Out]

    case class HandleSignal[-In, +Out, +Err, Sig, Resp, Evt](
        sigDef: SignalDef[Sig, Resp],
        sigHandler: SignalHandler[Sig, Evt, In],
        evtHandler: EventHandler[In, (Either[Err, Out], Resp), Event, Evt],
        errorCt: ErrorMeta[_],
    ) extends WIO[In, Err, Out] {
      def expects[Req1, Resp1](@unused signalDef: SignalDef[Req1, Resp1]): Option[HandleSignal[In, Out, Err, Req1, Resp1, Evt]] = {
        Some(this.asInstanceOf[HandleSignal[In, Out, Err, Req1, Resp1, Evt]]) // TODO
      }
    }

    case class HandleQuery[-In, +Err, +Out, -Qr, -QrState, +Resp](
        queryHandler: QueryHandler[Qr, QrState, Resp],
        inner: WIO[In, Err, Out],
    ) extends WIO[In, Err, Out]

    // theoretically state is not needed, it could be State.extract.flatMap(RunIO)
    case class RunIO[-In, +Err, +Out, Evt](
        buildIO: In => IO[Evt],
        evtHandler: EventHandler[In, Either[Err, Out], Event, Evt],
        errorCt: ErrorMeta[_],
    ) extends WIO[In, Err, Out]

    case class FlatMap[Err1 <: Err2, Err2, Out1, +Out2, -In](
        base: WIO[In, Err1, Out1],
        getNext: Out1 => WIO[Out1, Err2, Out2],
        errorCt: ErrorMeta[_],
    ) extends WIO[In, Err2, Out2]

    case class Map[In, Err, Out1, -In2, +Out2](
        base: WIO[In, Err, Out1],
        contramapInput: In2 => In,
        mapValue: (In2, Out1) => Out2,
    ) extends WIO[In2, Err, Out2]

    case class Pure[-In, +Err, +Out](
        value: In => Either[Err, Out],
        errorMeta: ErrorMeta[_],
    ) extends WIO[In, Err, Out]

    // TODO this should ne called `Never` or `Halt` or similar, as the workflow cant proceed from that point.
    case class Noop() extends WIO[Any, Nothing, Nothing]

    case class HandleError[-In, +Err, +Out, ErrIn](
        base: WIO[In, ErrIn, Out],
        handleError: ErrIn => WIO[In, Err, Out],
        handledErrorMeta: ErrorMeta[_],
        newErrorMeta: ErrorMeta[_],
    ) extends WIO[In, Err, Out]

    case class HandleErrorWith[-In, Err, +Out, +ErrOut](
        base: WIO[In, Err, Out],
        handleError: WIO[(In, Err), ErrOut, Out],
        handledErrorMeta: ErrorMeta[_],
        newErrorCt: ErrorMeta[_],
    ) extends WIO[In, ErrOut, Out]

    case class Named[-In, +Err, +Out](base: WIO[In, Err, Out], name: String, description: Option[String], errorMeta: ErrorMeta[_])
        extends WIO[In, Err, Out]

    case class AndThen[-In, +Err, Out1, +Out2](
        first: WIO[In, Err, Out1],
        second: WIO[Out1, Err, Out2],
    ) extends WIO[In, Err, Out2]

    // TODO name for condition
    case class DoWhile[-In, +Err, LoopOut, +Out](
        loop: WIO[LoopOut, Err, LoopOut],
        stopCondition: LoopOut => Option[Out],
        current: WIO[In, Err, LoopOut],
    ) extends WIO[In, Err, Out]

    case class Fork[-In, +Err, +Out](branches: Vector[Branch[In, Err, Out]]) extends WIO[In, Err, Out]

    // -----

    def handleSignal[StIn] = new HandleSignalPartiallyApplied1[StIn]

    class HandleSignalPartiallyApplied1[In] {
      def apply[Sig: ClassTag, Evt <: Event: ClassTag, Resp](@unused signalDef: SignalDef[Sig, Resp])(
          f: (In, Sig) => IO[Evt],
      ): HandleSignalPartiallyApplied2[Sig, In, Evt, Resp] = new HandleSignalPartiallyApplied2[Sig, In, Evt, Resp](SignalHandler(f), signalDef)
    }

    class HandleSignalPartiallyApplied2[Sig: ClassTag, In, Evt <: Event: ClassTag, Resp](
        signalHandler: SignalHandler[Sig, Evt, In],
        signalDef: SignalDef[Sig, Resp],
    ) {

      def handleEvent[Out](f: (In, Evt) => Out): HandleSignalPartiallyApplied3[Sig, In, Evt, Resp, Nothing, Out] = {
        new HandleSignalPartiallyApplied3(signalDef, signalHandler, (s: In, e: Evt) => f(s, e).asRight)
      }

      def handleEventWithError[Err, Out](
          f: (In, Evt) => Either[Err, Out],
      ): HandleSignalPartiallyApplied3[Sig, In, Evt, Resp, Err, Out] = {
        new HandleSignalPartiallyApplied3(signalDef, signalHandler, f)
      }
    }

    class HandleSignalPartiallyApplied3[Sig: ClassTag, In, Evt <: Event: ClassTag, Resp, Err, Out](
        signalDef: SignalDef[Sig, Resp],
        signalHandler: SignalHandler[Sig, Evt, In],
        handleEvent: (In, Evt) => Either[Err, Out],
    ) {
      def produceResponse(f: (In, Evt) => Resp)(implicit errorMeta: ErrorMeta[Err]): WIO[In, Err, Out] = {
        val combined                                                        = (s: In, e: Evt) => (handleEvent(s, e), f(s, e))
        val eventHandler: EventHandler[In, (Either[Err, Out], Resp), Event, Evt] = EventHandler(summon[ClassTag[Evt]].unapply, identity, combined)
        HandleSignal(signalDef, signalHandler, eventHandler, errorMeta)
      }
    }

    def handleQuery[StIn] = new HandleQueryPartiallyApplied1[StIn]

    class HandleQueryPartiallyApplied1[StIn] {
      def apply[Sig: ClassTag, Resp](@unused signalDef: SignalDef[Sig, Resp])(f: (StIn, Sig) => Resp)(implicit
          ct: ClassTag[StIn],
      ): HandleQueryPartiallyApplied2[StIn, Sig, Resp] = {
        new HandleQueryPartiallyApplied2(f)
      }
    }

    class HandleQueryPartiallyApplied2[QrSt: ClassTag, Sig: ClassTag, Resp](f: (QrSt, Sig) => Resp) {
      def apply[Err, Out, In](wio: WIO[In, Err, Out]): HandleQuery[In, Err, Out, Sig, QrSt, Resp] = {
        WIO.HandleQuery(QueryHandler(f), wio)
      }
    }

    def runIO[State] = new RunIOPartiallyApplied1[State]

    class RunIOPartiallyApplied1[StIn] {
      def apply[Evt <: Event: ClassTag](f: StIn => IO[Evt]): RunIOPartiallyApplied2[StIn, Evt] = {
        new RunIOPartiallyApplied2[StIn, Evt](f)
      }
    }

    class RunIOPartiallyApplied2[In, Evt <: Event: ClassTag](getIO: In => IO[Evt]) {
      def handleEvent[Out](f: (In, Evt) => Out): WIO[In, Nothing, Out] = {
        RunIO(getIO, EventHandler(summon[ClassTag[Evt]].unapply, identity, (s, e: Evt) => f(s, e).asRight), ErrorMeta.noError)
      }

      def handleEventWithError[Err, Out](
          f: (In, Evt) => Either[Err, Out],
      )(implicit errorCt: ErrorMeta[Err]): WIO[In, Err, Out] = {
        RunIO(getIO, EventHandler(summon[ClassTag[Evt]].unapply, identity, f), errorCt)
      }
    }

    def getState[St]: WIO[St, Nothing, St] = WIO.Pure(s => s.asRight, ErrorMeta.noError)

    def await[In](duration: Duration): WIO[In, Nothing, Unit] = ???

    def pure[St]: PurePartiallyApplied[St] = new PurePartiallyApplied

    class PurePartiallyApplied[In] {
      def apply[O](value: O): WIO[In, Nothing, O] = WIO.Pure(_ => Right(value), ErrorMeta.noError)

      def make[O](f: In => O): WIO[In, Nothing, O] = WIO.Pure(s => Right(f(s)), ErrorMeta.noError)

      def makeError[Err](f: In => Option[Err])(implicit ct: ErrorMeta[Err]): WIO[In, Err, In] = {
        WIO.Pure(s => f(s).map(Left(_)).getOrElse(Right(s)), ct)
      }
    }

    def unit[In] = pure[In](())

    def raise[In]: RaisePartiallyApplied[In] = new RaisePartiallyApplied

    class RaisePartiallyApplied[In] {
      def apply[Err](value: Err)(implicit ct: ErrorMeta[Err]): WIO[In, Err, Nothing] = WIO.Pure(s => Left(value), ct)
    }

    def repeat[Err, Out](action: WIO[Out, Err, Out]) = RepeatBuilder(action)

    case class RepeatBuilder[Err, Out](action: WIO[Out, Err, Out]) {
      def untilSome[Out1](f: Out => Option[Out1]): WIO[Out, Err, Out1] = WIO.DoWhile(action, f, action)
    }

    def fork[In]: ForkBuilder[In, Nothing, Nothing] = ForkBuilder(Vector())

    trait Branch[-In, +Err, +Out] {
      type I // Intermediate

      def condition: In => Option[I]

      def wio: WIO[(In, I), Err, Out]
    }

    object Branch {
      def apply[In, T, Err, Out](cond: In => Option[T], wio0: WIO[(In, T), Err, Out]): Branch[In, Err, Out] = {
        new Branch[In, Err, Out] {
          override type I = T
          override def condition: In => Option[I]  = cond
          override def wio: WIO[(In, I), Err, Out] = wio0
        }
      }
    }

    // can be removed and replaced with direct instance of WIO.Fork?
    case class ForkBuilder[-In, +Err, +Out](branches: Vector[Branch[In, Err, Out]]) {
      def branch[T, Err1 >: Err, Out1 >: Out, In1 <: In](cond: In1 => Option[T])(
          wio: WIO[(In1, T), Err1, Out1],
      ): ForkBuilder[In1, Err1, Out1] = addBranch(Branch(cond, wio))

      def addBranch[T, Err1 >: Err, Out1 >: Out, In1 <: In](
          b: Branch[In1, Err1, Out1],
      ): ForkBuilder[In1, Err1, Out1] = ForkBuilder(branches.appended(b))

      def done: WIO[In, Err, Out] = WIO.Fork(branches)
    }

    def branch[In]: BranchBuilder[In] = BranchBuilder()

    case class BranchBuilder[In]() {
      def when[Err, Out, StOut](cond: In => Boolean)(wio: WIO[In, Err, Out]): Branch[In, Err, Out] =
        Branch(cond.andThen(Option.when(_)(())), wio.transformInput((x: (In, Any)) => x._1))

      def create[T, Err, Out, StOut](cond: In => Option[T])(wio: WIO[(In, T), Err, Out]): Branch[In, Err, Out] =
        Branch(cond, wio)
    }

    def embed[In, Err, Out](wio: WIOT[In, Err, Out])(embedding0: WorkflowEmbedding[wio.context.type, parentCtx.type]): WIO[In, Err, Out] = {
      val m = new WorkflowConversionEvaluator[wio.context.type] {
        override val destCtx: parentCtx.type                   = parentCtx
        override val c: wio.context.type                       = wio.context
        val embedding: WorkflowEmbedding[c.type, destCtx.type] = embedding0
      }
      //    val wioCasted = wio.asInstanceOf[m.c.WIO[E, O, SIn, SOut]]
      m.convert(wio.asParents) // TODO cast
    }
  }
}
