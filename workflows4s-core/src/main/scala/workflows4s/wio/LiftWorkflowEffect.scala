package workflows4s.wio

trait LiftWorkflowEffect[Ctx <: WorkflowContext, F2[_]] {
  def apply[A](fa: WCEffect[Ctx][A]): F2[A]

  def asPoly: WCEffectLift[Ctx, F2] = [a] => (fa: WCEffect[Ctx][a]) => apply(fa)
}

object LiftWorkflowEffect {

  type Between[Ctx1 <: WorkflowContext, Ctx2 <: WorkflowContext] = LiftWorkflowEffect[Ctx1, WCEffect[Ctx2]]

  def through[Ctx <: WorkflowContext, F[_]](using
      base: LiftWorkflowEffect[Ctx, F],
  )[G[_]](transform: [A] => F[A] => G[A]): LiftWorkflowEffect[Ctx, G] =
    new LiftWorkflowEffect[Ctx, G] {
      override def apply[A](fa: WCEffect[Ctx][A]): G[A] = transform(base.apply(fa))
    }

  given [Eff[_], Ctx <: WorkflowContext.AuxEff[Eff]]: LiftWorkflowEffect[Ctx, Eff] = new LiftWorkflowEffect[Ctx, Eff] {
    override def apply[A](fa: WCEffect[Ctx][A]): Eff[A] = fa.asInstanceOf[Eff[A]]
  }

  // Match-type bridge: Eff[A] → WCEffect[Ctx][A] (symmetric to the extract bridge above).
  private def injectEffect[Eff[_], Ctx <: WorkflowContext.AuxEff[Eff]]: [A] => Eff[A] => WCEffect[Ctx][A] =
    [A] => (fa: Eff[A]) => fa.asInstanceOf[WCEffect[Ctx][A]]

  given [Eff[_], Ctx1 <: WorkflowContext.AuxEff[Eff], Ctx2 <: WorkflowContext.AuxEff[Eff]](using
      base: LiftWorkflowEffect[Ctx1, Eff],
  ): LiftWorkflowEffect[Ctx1, WCEffect[Ctx2]] =
    through[Ctx1, Eff](using base)[WCEffect[Ctx2]](injectEffect[Eff, Ctx2])

}
