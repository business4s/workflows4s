package workflow4s.wio.internal

import workflow4s.wio.WIO

object WIOUtils {

  // strips down all the decorators
  def getFirstRaw(wio: WIO[?, ?, ?, ?]): WIO[?, ?, ?, ?] = wio match {
    case x @ WIO.HandleSignal(_, _, _, _)      => x
    case x @ WIO.RunIO(_, _, _)                => x
    case WIO.FlatMap(base, _, _)               => getFirstRaw(base)
    case WIO.Map(base, _, _)                   => getFirstRaw(base)
    case x @ WIO.Pure(_, _)                    => x
    case x @ WIO.Noop()                        => x
    case WIO.HandleError(base, _, _, _)        => getFirstRaw(base)
    case WIO.HandleErrorWith(base, _, _, _, _) => getFirstRaw(base)
    case WIO.Named(base, _, _, _)              => getFirstRaw(base)
    case WIO.AndThen(first, _)                 => getFirstRaw(first)
    case WIO.Loop(_, _, current, _, _, _)      => getFirstRaw(current)
    case x @ WIO.Fork(_, _)                       => x
    case WIO.Embedded(inner, _, _)             => getFirstRaw(inner)
    case WIO.HandleInterruption(base, _)       => getFirstRaw(base)
    case x @ WIO.Timer(_, _, _, _, _)             => x
    case x @ WIO.AwaitingTime(_, _, _)         => x
  }

}
