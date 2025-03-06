package workflows4s.wio.builders

import workflows4s.wio.{WCState, WIO, WorkflowContext}

object ParallelBuilder {

  trait Step0[Ctx <: WorkflowContext]() {

    def parallel: ParallelStep1 = ParallelStep1()

    case class ParallelStep1() {

      def taking[In]: Step2[In] = Step2()

      case class Step2[In]() {

        def withInterimState[InterimState <: WCState[Ctx]](initial: In => InterimState): Step3[Nothing, InterimState, EmptyTuple] =
          Step3(initial, Seq())

        case class Step3[Err, InterimState <: WCState[Ctx], OutAcc <: Tuple](
            protected val initial: In => InterimState,
            protected val elems: Seq[WIO.Parallel.Element[Ctx, In, Err, WCState[Ctx], InterimState]],
        ) {

          def withElement[Out <: WCState[Ctx], NewErr >: Err](
              logic: WIO[In, Err, Out, Ctx],
              incorporatedWith: (InterimState, WCState[Ctx]) => InterimState,
          ): Step3[NewErr, InterimState, Out *: OutAcc] = this.copy(elems = elems.appended(WIO.Parallel.Element(logic, incorporatedWith)))

          def producingOutputWith[Out <: WCState[Ctx]](f: OutAcc => Out): WIO.Parallel[Ctx, In, Err, Out, InterimState] = WIO.Parallel(
            elements = elems,
            formResult = resultsSeq => f(Tuple.fromArray(resultsSeq.toArray[Any]).asInstanceOf[OutAcc]),
            initialInterimState = initial,
          )

        }

      }
    }

  }

}
