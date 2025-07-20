package workflows4s.wio.builders

import workflows4s.wio.internal.WorkflowEmbedding
import workflows4s.wio.model.{ModelUtils, WIOMeta}
import workflows4s.wio.{SignalRouter, WCEvent, WCState, WIO, WorkflowContext}

object ForEachBuilder {

  trait Step0[Ctx <: WorkflowContext]() {

    def forEach[In]: ForEachStep1[In] = ForEachStep1()

    case class ForEachStep1[In]() {

      def apply[Elem](getElements: In => Set[Elem]): Step2[Elem] = Step2(getElements)

      case class Step2[Elem](private val getElements: In => Set[Elem]) {

        def execute[InnerCtx <: WorkflowContext]: Step2_1[InnerCtx] = Step2_1()

        case class Step2_1[InnerCtx <: WorkflowContext]() {
          def apply[Err, Out <: WCState[InnerCtx]](
              wio: WIO[Elem, Err, Out, InnerCtx],
              initialState: => WCState[InnerCtx],
          ): Step3[InnerCtx, Err, Out] =
            Step3(wio, () => initialState)

          case class Step3[InnerCtx <: WorkflowContext, Err, ElemOut <: WCState[InnerCtx]](
              private val forEachElem: WIO[Elem, Err, ElemOut, InnerCtx],
              private val initialState: () => WCState[InnerCtx],
          ) {

            def withEventsEmbeddedThrough(embedding: WorkflowEmbedding.Event[(Elem, WCEvent[InnerCtx]), WCEvent[Ctx]]): Step4 = Step4(embedding)

            case class Step4(private val eventEmbedding: WorkflowEmbedding.Event[(Elem, WCEvent[InnerCtx]), WCEvent[Ctx]]) {

              def withInterimState[InterimState <: WCState[Ctx]](in: In => InterimState): Step5[InterimState] = Step5(in)

              case class Step5[InterimState <: WCState[Ctx]](private val initial: In => InterimState) {

                def incorporatingChangesThrough(f: (Elem, WCState[InnerCtx], InterimState) => InterimState): Step6 = Step6(f)

                case class Step6(private val incorporatingChangesThrough: (Elem, WCState[InnerCtx], InterimState) => InterimState) {

                  def withOutputBuiltWith[Out <: WCState[Ctx]](outputBuilder: (In, Map[Elem, ElemOut]) => Out): Step7[Out] = Step7(outputBuilder)

                  def withInterimStateAsOutput: Step7[InterimState] = withOutputBuiltWith[InterimState]((in, outs) =>
                    outs.foldLeft(initial(in))((interim, result) => incorporatingChangesThrough(result._1, result._2, interim)),
                  )

                  case class Step7[Out <: WCState[Ctx]](private val outputBuilder: (In, Map[Elem, ElemOut]) => Out) {

                    def withSignalsWrappedWith(signalWrapper: SignalRouter.Receiver[Elem, InterimState]): Step8 = Step8(signalWrapper)

                    case class Step8(private val signalRouter: SignalRouter.Receiver[Elem, InterimState]) {
                      def named(name: String)                   = build(Some(name))
                      def autoNamed()(using n: sourcecode.Name) = named(ModelUtils.prettifyName(n.value))

                      def build(name: Option[String]): WIO.ForEach[Ctx, In, Err, Out, Elem, InnerCtx, ElemOut, InterimState] = {
                        WIO.ForEach(
                          getElements = getElements,
                          elemWorkflow = forEachElem,
                          initialElemState = initialState,
                          eventEmbedding = eventEmbedding,
                          initialInterimState = initial,
                          incorporatePartial = incorporatingChangesThrough,
                          buildOutput = outputBuilder,
                          stateOpt = None,
                          signalRouter = signalRouter,
                          meta = WIOMeta.ForEach(name),
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
  }

}
