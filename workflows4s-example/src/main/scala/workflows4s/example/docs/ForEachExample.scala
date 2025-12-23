package workflows4s.example.docs

import cats.Id
import workflows4s.example.docs.Context.WIO
import workflows4s.runtime.WorkflowInstance
import workflows4s.cats.IOWorkflowContext
import workflows4s.wio.{BasicSignalRouter, SignalDef, SignalRouter, SimpleSignalRouter}
import workflows4s.wio.internal.WorkflowEmbedding

import scala.annotation.nowarn

object ForEachExample {

  object draft {
    // draft_start
    val subWorkflow  = WIO.draft.step()
    val forEachDraft = WIO.draft.forEach(subWorkflow)
    // draft_end
  }
  object real  {
    case class SubWorkflowState()
    case class SubWorkflowEvent()
    object SubWorkflowContext extends IOWorkflowContext {
      override type State = SubWorkflowState
      override type Event = SubWorkflowEvent
    }

    // real_start
    type Element
    type Input
    def elementWorkflow: SubWorkflowContext.WIO[Element, Nothing, SubWorkflowState]         = SubWorkflowContext.WIO.pure(SubWorkflowState()).autoNamed
    def getElements(input: Input): Set[Element]                                             = ???
    def initialElementState: SubWorkflowState                                               = ???
    def buildInterimState(input: Input, subStates: Map[Element, SubWorkflowState]): MyState = ???
    def buildOutput(input: Input, results: Map[Element, SubWorkflowState]): MyState         = ???

    def eventEmbedding: WorkflowEmbedding.Event[(Element, SubWorkflowEvent), MyEventBase] =
      new WorkflowEmbedding.Event[(Element, SubWorkflowEvent), MyEventBase] {
        override def convertEvent(e: (Element, SubWorkflowEvent)): MyEventBase           = ???
        override def unconvertEvent(e: MyEventBase): Option[(Element, SubWorkflowEvent)] = ???
      }

    def signalRouter: SignalRouter.Receiver[Element, MyState] = SimpleSignalRouter[Element]()

    val forEachStep = WIO
      .forEach[Input](getElements)
      .execute[SubWorkflowContext.Ctx](elementWorkflow, initialElementState)
      .withEventsEmbeddedThrough(eventEmbedding)
      .withInterimState(buildInterimState)
      .withOutputBuiltWith(buildOutput)
      .withSignalsWrappedWith(signalRouter)
      .autoNamed()
    // real_end

    @nowarn("msg=unused")
    object signal_send {
      type Req
      type Resp
      def signalDef: SignalDef[Req, Resp]                 = ???
      def request: Req                                    = ???
      val workflowInstance: WorkflowInstance[Id, MyState] = ???

      object signal_basic {
        // signal_basic_start
        type Key
        object SigRouter extends BasicSignalRouter[Key, Element, SubWorkflowState] {
          override def extractElem(state: SubWorkflowState, key: Key): Option[Element] = ???
        }

        def key: Key = ???
        workflowInstance.deliverRoutedSignal(SigRouter, key, signalDef, request)
        // signal_basic_end
      }

      object simple {
        // signal_simple_start
        val signalRouter = SimpleSignalRouter[Element]

        def element: Element = ???
        workflowInstance.deliverRoutedSignal(signalRouter, element, signalDef, request)
        // signal_simple_end
      }
    }

  }
}
