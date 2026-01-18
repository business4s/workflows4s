package workflows4s

import workflows4s.wio.model.WIOExecutionProgress

import java.time.Duration

private[workflows4s] object RenderUtils {

  def humanReadableDuration(duration: Duration): String = duration.toString.substring(2).replaceAll("(\\d[HMS])(?!$)", "$1 ").toLowerCase

  def hasStarted(model: WIOExecutionProgress[?]): Boolean = model match {
    case WIOExecutionProgress.Sequence(steps)                      => hasStarted(steps.head)
    case WIOExecutionProgress.Dynamic(_)                           => false
    case WIOExecutionProgress.RunIO(_, result)                     => result.isDefined
    case WIOExecutionProgress.HandleSignal(_, result)              => result.isDefined

    case WIOExecutionProgress.End(result)                          => result.isDefined
    case WIOExecutionProgress.Pure(_, result)                      => result.isDefined
    case WIOExecutionProgress.Loop(_, _, _, history)               => history.nonEmpty
    case WIOExecutionProgress.Fork(_, _, selected)                 => selected.isDefined
    case WIOExecutionProgress.Interruptible(base, trigger, _, _)   => hasStarted(base) || hasStarted(trigger)
    case WIOExecutionProgress.Timer(_, result)                     => result.isDefined
    case WIOExecutionProgress.Parallel(elems, _)                   => elems.exists(hasStarted)
    case WIOExecutionProgress.Checkpoint(base, result)             => result.isDefined || hasStarted(base)
    case WIOExecutionProgress.Recovery(result)                     => result.isDefined
    case WIOExecutionProgress.Retried(base)                        => hasStarted(base)
    case WIOExecutionProgress.ForEach(result, _, subProgresses, _) => result.isDefined || subProgresses.values.exists(hasStarted)
  }

}
