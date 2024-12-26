package workflows4s.wio.internal

import workflows4s.wio.WIO

object WIOUtils {

  // strips down all the decorators
  def getFirstRaw(wio: WIO[?, ?, ?, ?]): WIO[?, ?, ?, ?] = wio match {
    case _ @WIO.HandleSignal(_, _, _, _)    => wio // TODO some type inference issues
    case _ @WIO.RunIO(_, _, _)              => wio
    case WIO.FlatMap(base, _, _)            => getFirstRaw(base)
    case WIO.Transform(base, _, _)                => getFirstRaw(base)
    case _ @WIO.Pure(_, _)                  => wio
    case _ @WIO.End()                      => wio
    case WIO.HandleError(base, _, _, _)     => getFirstRaw(base)
    case WIO.HandleErrorWith(base, _, _, _) => getFirstRaw(base)
    case WIO.Named(base, _, _, _)           => getFirstRaw(base)
    case WIO.AndThen(first, _)              => getFirstRaw(first)
    case WIO.Loop(_, _, current, _, _, _)   => getFirstRaw(current)
    case _ @WIO.Fork(_, _)                  => wio
    case WIO.Embedded(inner, _, _)          => getFirstRaw(inner)
    case WIO.HandleInterruption(base, _)    => getFirstRaw(base)
    case _ @WIO.Timer(_, _, _, _)           => wio
    case _ @WIO.AwaitingTime(_, _)          => wio
  }

}
