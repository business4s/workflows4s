package workflows4s

import workflows4s.wio.model.WIOExecutionProgress

import java.time.Duration

private[workflows4s] object RenderUtils {

  def humanReadableDuration(duration: Duration): String = duration.toString.substring(2).replaceAll("(\\d[HMS])(?!$)", "$1 ").toLowerCase

  def hasStarted(model: WIOExecutionProgress[?]): Boolean = model match {
    case WIOExecutionProgress.Sequence(steps)                               => hasStarted(steps.head)
    case WIOExecutionProgress.Dynamic(meta)                                 => false
    case WIOExecutionProgress.RunIO(meta, result)                           => result.isDefined
    case WIOExecutionProgress.HandleSignal(meta, result)                    => result.isDefined
    case WIOExecutionProgress.HandleError(base, handler, meta, result)      => hasStarted(base) || hasStarted(handler)
    case WIOExecutionProgress.End(result)                                   => result.isDefined
    case WIOExecutionProgress.Pure(meta, result)                            => result.isDefined
    case WIOExecutionProgress.Loop(base, onRestart, meta, history)          => history.nonEmpty
    case WIOExecutionProgress.Fork(branches, meta, selected)                => selected.isDefined
    case WIOExecutionProgress.Interruptible(base, trigger, handler, result) => hasStarted(base) || hasStarted(trigger)
    case WIOExecutionProgress.Timer(meta, result)                           => result.isDefined
    case WIOExecutionProgress.Parallel(elems, _)                            => elems.exists(hasStarted)
    case WIOExecutionProgress.Checkpoint(base, result)                      => result.isDefined || hasStarted(base)
    case WIOExecutionProgress.Recovery(result)                              => result.isDefined
  }

}
