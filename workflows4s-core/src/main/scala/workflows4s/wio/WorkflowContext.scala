package workflows4s.wio

import cats.Applicative
import workflows4s.wio.builders.AllBuilders

/** Defines the type-level environment for a workflow: its State, Event, and effect types.
  *
  * Extend this trait to create a workflow context, then use the inner `WIO` object as the entry point for building workflow steps with the builder
  * DSL.
  *
  * {{{
  * object MyCtx extends WorkflowContext {
  *   type Effect = IO
  *   type Event = MyEvent
  *   type State = MyState
  * }
  *
  * val step: MyCtx.WIO[MyState, Nothing, MyState] = MyCtx.WIO.pure(...)
  * }}}
  */
trait WorkflowContext { ctx: WorkflowContext =>
  type Effect[_]
  type Event
  type State
  type Ctx = WorkflowContext.AUX[State, Event, Effect]

  type WIO[-In, +Err, +Out <: State] = workflows4s.wio.WIO[In, Err, Out, Ctx]
  object WIO extends AllBuilders[Ctx] {
    export workflows4s.wio.WIO.{Branch as _, Draft as _, Initial as _, Interruption as _, build as _, *}

    type Branch[-In, +Err, +Out <: State]  = workflows4s.wio.WIO.Branch[In, Err, Out, Ctx, ?]
    type Interruption[+Err, +Out <: State] = workflows4s.wio.WIO.Interruption[Ctx, Err, Out]
    type Draft                             = WIO[Any, Nothing, Nothing]
    type Initial                           = workflows4s.wio.WIO.Initial[Ctx]

  }

  // Bridge: WCEffect[Ctx] is always Effect at runtime, but the Scala 3 match type system fails to reduce our AUX.
  // These bridges provide:
  // 1. Typeclass instances for WCEffect[Ctx] derived from Effect instances
  // 2. Implicit conversions between Effect and WCEffect[Ctx]
  implicit def wcEffectApplicative(implicit a: Applicative[Effect]): Applicative[WCEffect[Ctx]] = a.asInstanceOf[Applicative[WCEffect[Ctx]]]
  implicit def effectToWCEffect[A](fa: Effect[A]): WCEffect[Ctx][A]                             = fa.asInstanceOf[WCEffect[Ctx][A]]
}

object WorkflowContext {
  private type AuxS[_S]             = WorkflowContext { type State = _S }
  private type AuxE[_E]             = WorkflowContext { type Event = _E }
  type AuxEff[_F[_]] >: WorkflowContext { type Effect[T] = _F[T] } <: WorkflowContext { type Effect[T] = _F[T] }
  type State[T <: WorkflowContext]  = T match {
    case AuxS[s] => s
  }
  type Event[T <: WorkflowContext]  = T match {
    case AuxE[s] => s
  }
  type Effect[T <: WorkflowContext] = [A] =>> T match {
    case AuxEff[f] => f[A]
  }

  type AUX[St, Evt, Eff[_]] = WorkflowContext { type State = St; type Event = Evt; type Effect[T] = Eff[T] }
}

trait LiftWorkflowEffect[Ctx <: WorkflowContext, F2[_]] {
  def apply[A](fa: WCEffect[Ctx][A]): F2[A]

  def asPoly: WCEffectLift[Ctx, F2] = [a] => (fa: WCEffect[Ctx][a]) => apply(fa)
}

object LiftWorkflowEffect {

  type Between[Ctx1 <: WorkflowContext, Ctx2 <: WorkflowContext] = LiftWorkflowEffect[Ctx1, WCEffect[Ctx2]]

  given [Eff[_], Ctx <: WorkflowContext.AuxEff[Eff]]: LiftWorkflowEffect[Ctx, Eff] = new LiftWorkflowEffect[Ctx, Eff] {
    override def apply[A](fa: WCEffect[Ctx][A]): Eff[A] = fa.asInstanceOf[Eff[A]]
  }

  def andThen[Ctx <: WorkflowContext, F[_], G[_]](base: LiftWorkflowEffect[Ctx, F], transform: [A] => F[A] => G[A]): LiftWorkflowEffect[Ctx, G] =
    new LiftWorkflowEffect[Ctx, G] {
      override def apply[A](fa: WCEffect[Ctx][A]): G[A] = transform(base.apply(fa))
    }

  given [Eff[_], Ctx1 <: WorkflowContext.AuxEff[Eff], Ctx2 <: WorkflowContext.AuxEff[Eff]]: LiftWorkflowEffect[Ctx1, WCEffect[Ctx2]] =
    new LiftWorkflowEffect[Ctx1, WCEffect[Ctx2]] {
      override def apply[A](fa: WCEffect[Ctx1][A]): WCEffect[Ctx2][A] = fa.asInstanceOf[WCEffect[Ctx2][A]]
    }

}
