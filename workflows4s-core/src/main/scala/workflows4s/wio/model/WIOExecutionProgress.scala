package workflows4s.wio.model

import cats.implicits.toTraverseOps

/** Tree structure capturing both the static workflow shape and the runtime state reached at each node.
  * Convertible to [[WIOModel]] for serialization.
  */
sealed trait WIOExecutionProgress[+State] {
  def result: WIOExecutionProgress.ExecutionResult[State]
  def isExecuted: Boolean = result.isDefined
  def toModel: WIOModel

  def map[NewState](f: State => Option[NewState]): WIOExecutionProgress[NewState]
}

object WIOExecutionProgress {

  case class ExecutedResult[+State](
      value: Either[Any, State],
      index: Int,
  ) {
    def mapValue[NewState](f: State => Option[NewState]): Option[ExecutedResult[NewState]] = {
      value.traverse(f).map(newValue => copy(value = newValue))
    }

  }

  type ExecutionResult[+State] = Option[ExecutedResult[State]]

  sealed trait Interruption[+State] extends WIOExecutionProgress[State] {
    def toModel: WIOModel.Interruption
    def map[NewState](f: State => Option[NewState]): Interruption[NewState]
  }

  case class Sequence[State](steps: Seq[WIOExecutionProgress[State]]) extends WIOExecutionProgress[State] {
    assert(steps.size >= 2, "Sequence should contain at least two steps")
    def result: ExecutionResult[State] = steps.lastOption.flatMap(_.result)

    override lazy val toModel: WIOModel                                                      = WIOModel.Sequence(steps.map(_.toModel))
    override def map[NewState](f: State => Option[NewState]): WIOExecutionProgress[NewState] = Sequence(steps.map(_.map(f)))
  }

  case class Dynamic(meta: WIOMeta.Dynamic) extends WIOExecutionProgress[Nothing] {
    def result: ExecutionResult[Nothing] = None // if it was executed, it wouldn't have been dynamic

    override lazy val toModel: WIOModel                                                        = WIOModel.Dynamic(meta)
    override def map[NewState](f: Nothing => Option[NewState]): WIOExecutionProgress[NewState] = this
  }

  case class RunIO[State](meta: WIOMeta.RunIO, result: ExecutionResult[State]) extends WIOExecutionProgress[State] {
    override lazy val toModel: WIOModel                                                      = WIOModel.RunIO(meta)
    override def map[NewState](f: State => Option[NewState]): WIOExecutionProgress[NewState] =
      RunIO(meta, result.flatMap(_.mapValue(f)))

  }

  case class HandleSignal[State](meta: WIOMeta.HandleSignal, result: ExecutionResult[State])
      extends WIOExecutionProgress[State]
      with Interruption[State] {
    override lazy val toModel: WIOModel.Interruption                                 = WIOModel.HandleSignal(meta)
    override def map[NewState](f: State => Option[NewState]): Interruption[NewState] =
      HandleSignal(meta, result.flatMap(_.mapValue(f)))
  }

  case class HandleError[State](
      base: WIOExecutionProgress[State],
      handler: WIOExecutionProgress[State],
      meta: WIOMeta.HandleError,
      result: ExecutionResult[State],
  ) extends WIOExecutionProgress[State] {
    override lazy val toModel: WIOModel                                                      = WIOModel.HandleError(base.toModel, handler.toModel, meta)
    override def map[NewState](f: State => Option[NewState]): WIOExecutionProgress[NewState] =
      HandleError(base.map(f), handler.map(f), meta, result.flatMap(_.mapValue(f)))
  }

  case class End[State](result: ExecutionResult[State]) extends WIOExecutionProgress[State] {
    override lazy val toModel: WIOModel = WIOModel.End

    override def map[NewState](f: State => Option[NewState]): WIOExecutionProgress[NewState] = End(result.flatMap(_.mapValue(f)))
  }

  case class Pure[State](meta: WIOMeta.Pure, result: ExecutionResult[State]) extends WIOExecutionProgress[State] {
    override lazy val toModel: WIOModel                                                      = WIOModel.Pure(meta)
    override def map[NewState](f: State => Option[NewState]): WIOExecutionProgress[NewState] = Pure(meta, result.flatMap(_.mapValue(f)))
  }

  case class Loop[State](
      base: WIOModel,
      onRestart: Option[WIOModel],
      meta: WIOMeta.Loop,
      history: Seq[WIOExecutionProgress[State]], // last one contains current status
  ) extends WIOExecutionProgress[State] {
    override def result: ExecutionResult[State]                              = history.lastOption.flatMap(_.result)
    override lazy val toModel: WIOModel                                      = WIOModel.Loop(base, onRestart, meta)
    override def map[NewState](f: State => Option[NewState]): Loop[NewState] = Loop(base, onRestart, meta, history.map(_.map(f)))
  }

  case class Fork[State](branches: Vector[WIOExecutionProgress[State]], meta: WIOMeta.Fork, selected: Option[Int])
      extends WIOExecutionProgress[State] {
    assert(selected.forall(branches.indices.contains))

    override def result: ExecutionResult[State]                              = selected.flatMap(branches.lift).flatMap(_.result)
    override lazy val toModel: WIOModel                                      = WIOModel.Fork(branches.map(_.toModel), meta)
    override def map[NewState](f: State => Option[NewState]): Fork[NewState] = Fork(branches.map(_.map(f)), meta, selected)
  }

  // handle flow is optional because handling might end on single step(the trigger)
  case class Interruptible[State](
      base: WIOExecutionProgress[State],
      trigger: Interruption[State],
      handler: Option[WIOExecutionProgress[State]],
      result: ExecutionResult[State],
  ) extends WIOExecutionProgress[State] {
    assert(!(base.isExecuted && trigger.isExecuted), "only one of base and trigger should be executed")
    override lazy val toModel: WIOModel                                               = WIOModel.Interruptible(base.toModel, trigger.toModel, handler.map(_.toModel))
    override def map[NewState](f: State => Option[NewState]): Interruptible[NewState] =
      Interruptible(base.map(f), trigger.map(f), handler.map(_.map(f)), result.flatMap(_.mapValue(f)))
  }

  case class Timer[State](meta: WIOMeta.Timer, result: ExecutionResult[State]) extends WIOExecutionProgress[State] with Interruption[State] {
    override lazy val toModel: WIOModel.Interruption                          = WIOModel.Timer(meta)
    override def map[NewState](f: State => Option[NewState]): Timer[NewState] = Timer(meta, result.flatMap(_.mapValue(f)))
  }

  case class Parallel[State](elements: Seq[WIOExecutionProgress[State]], result: ExecutionResult[State]) extends WIOExecutionProgress[State] {
    override lazy val toModel: WIOModel.Parallel                                 = WIOModel.Parallel(elements.map(_.toModel))
    override def map[NewState](f: State => Option[NewState]): Parallel[NewState] =
      Parallel(elements.map(_.map(f)), result.flatMap(_.mapValue(f)))
  }

  case class Checkpoint[State](base: WIOExecutionProgress[State], result: ExecutionResult[State]) extends WIOExecutionProgress[State] {
    override lazy val toModel: WIOModel.Checkpoint                                 = WIOModel.Checkpoint(base.toModel)
    override def map[NewState](f: State => Option[NewState]): Checkpoint[NewState] =
      Checkpoint(base.map(f), result.flatMap(_.mapValue(f)))
  }
  case class Recovery[State](result: ExecutionResult[State])                                      extends WIOExecutionProgress[State] {
    override lazy val toModel: WIOModel.Recovery                                 = WIOModel.Recovery()
    override def map[NewState](f: State => Option[NewState]): Recovery[NewState] =
      Recovery(result.flatMap(_.mapValue(f)))
  }
  case class Retried[State](base: WIOExecutionProgress[State])                                    extends WIOExecutionProgress[State] {
    override def result: ExecutionResult[State]                                 = base.result
    override lazy val toModel: WIOModel.Retried                                 = WIOModel.Retried(base.toModel)
    override def map[NewState](f: State => Option[NewState]): Retried[NewState] = Retried(base.map(f))
  }
  case class ForEach[State, ElemId, ElemState](
      result: ExecutionResult[State],
      forEach: WIOModel,
      subProgresses: Map[ElemId, WIOExecutionProgress[ElemState]],
      meta: WIOMeta.ForEach,
  ) extends WIOExecutionProgress[State] {
    override lazy val toModel: WIOModel.ForEach                                                    = WIOModel.ForEach(forEach, meta)
    override def map[NewState](f: State => Option[NewState]): ForEach[NewState, ElemId, ElemState] = this.copy(result.flatMap(_.mapValue(f)))
  }

  def fromModel(model: WIOModel): WIOExecutionProgress[Nothing]                                               = model match {
    case x: WIOModel.Interruption                       => fromModelInterruption(x)
    case WIOModel.Sequence(steps)                       => Sequence(steps.map(fromModel))
    case WIOModel.Dynamic(meta)                         => Dynamic(meta)
    case WIOModel.RunIO(meta)                           => RunIO(meta, None)
    case WIOModel.HandleError(base, handler, meta)      => HandleError(fromModel(base), fromModel(handler), meta, None)
    case WIOModel.End                                   => End(None)
    case WIOModel.Pure(meta)                            => Pure(meta, None)
    case WIOModel.Loop(base, onRestart, meta)           => Loop(base, onRestart, meta, Seq.empty)
    case WIOModel.Fork(branches, meta)                  => Fork(branches.map(fromModel), meta, None)
    case WIOModel.Parallel(elems)                       => Parallel(elems.map(fromModel), None)
    case WIOModel.Checkpoint(base)                      => Checkpoint(fromModel(base), None)
    case WIOModel.Recovery()                            => Recovery(None)
    case WIOModel.Retried(base)                         => Retried(fromModel(base))
    case WIOModel.ForEach(forEach, meta)                => ForEach(None, forEach, Map.empty, meta)
    case WIOModel.Interruptible(base, trigger, handler) =>
      Interruptible(fromModel(base), fromModelInterruption(trigger), handler.map(fromModel), None)
  }
  private def fromModelInterruption(model: WIOModel.Interruption): WIOExecutionProgress.Interruption[Nothing] = model match {
    case WIOModel.HandleSignal(meta) => HandleSignal(meta, None)
    case WIOModel.Timer(meta)        => Timer(meta, None)
  }

}
