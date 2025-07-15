package workflows4s.wio.builders

import workflows4s.wio.internal.{SignalWrapper, WorkflowEmbedding}
import workflows4s.wio.{WCEvent, WCState, WIO, WorkflowContext}

object ForEachBuilder {

  trait Step0[Ctx <: WorkflowContext]() {

    def forEach[In]: ParallelStep1[In] = ParallelStep1()

    case class ParallelStep1[In]() {

      def apply[Elem](getElements: In => Set[Elem]): Step2[Elem] = Step2(getElements)

      case class Step2[Elem](getElements: In => Set[Elem]) {

        def execute[InnerCtx <: WorkflowContext, Err, Out <: WCState[InnerCtx]](wio: WIO[Elem, Err, Out, InnerCtx], initialState: WCState[InnerCtx]) =
          Step3(wio, initialState)

        case class Step3[InnerCtx <: WorkflowContext, Err, ElemOut <: WCState[InnerCtx]](
            forEachElem: WIO[Elem, Err, ElemOut, InnerCtx],
            initialState: WCState[InnerCtx],
        ) {

          def withEventsEmbeddedThrough(embedding: WorkflowEmbedding.Event[(Elem, WCEvent[InnerCtx]), WCEvent[Ctx]]) = Step4(embedding)

          case class Step4(eventEmbedding: WorkflowEmbedding.Event[(Elem, WCEvent[InnerCtx]), WCEvent[Ctx]]) {

            def withInterimState[InterimState <: WCState[Ctx]](in: In => InterimState) = Step5(in)

            case class Step5[InterimState <: WCState[Ctx]](initial: In => InterimState) {

              def incorporatingChangesThrough(f: (Elem, WCState[InnerCtx], InterimState) => InterimState) = Step6(f)

              case class Step6(incorporatingChangesThrough: (Elem, WCState[InnerCtx], InterimState) => InterimState) {

                def withOutputBuiltWith[Out <: WCState[Ctx]](outputBuilder: Map[Elem, ElemOut] => Out) = Step7(outputBuilder)

                case class Step7[Out <: WCState[Ctx]](outputBuilder: Map[Elem, ElemOut] => Out) {

                  def withSignalsWrappedWith(
                      signalWrapper: SignalWrapper[Elem],
                  ): WIO.ForEach[Ctx, In, Err, Out, Elem, InnerCtx, ElemOut, InterimState] = {
                    WIO.ForEach(
                      getElements,
                      forEachElem,
                      () => initialState,
                      eventEmbedding,
                      initial,
                      incorporatingChangesThrough,
                      outputBuilder,
                      None,
                      signalWrapper,
                    )
                  }
                }
              }
            }

          }

        }

      }
    }

  }

}
