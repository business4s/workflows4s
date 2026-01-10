package workflows4s.wio.experimental

trait Workflow {
  type Event
  type State

  sealed trait WIO[-In, +Err, +Out] {
    def flatMap[Err1 >: Err, Out1](f: Out => WIO[Out, Err1, Out1]): WIO[In, Err1, Out1] =
      WIO.FlatMap(this, f)

    def map[Out1](f: Out => Out1): WIO[In, Err, Out1] =
      this.flatMap(out => WIO.Pure(f(out)))
  }

  object WIO {
    case class Pure[+Out](value: Out) extends WIO[Any, Nothing, Out]

    case class FlatMap[In, Err, Out, Out1](
        base: WIO[In, Err, Out],
        f: Out => WIO[Out, Err, Out1],
    ) extends WIO[In, Err, Out1]
  }
}
