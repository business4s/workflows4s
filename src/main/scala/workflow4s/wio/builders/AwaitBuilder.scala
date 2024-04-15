package workflow4s.wio.builders

import workflow4s.wio.*
import workflow4s.wio.WIO.Timer
import workflow4s.wio.WIO.Timer.DurationSource
import workflow4s.wio.internal.EventHandler
import workflow4s.wio.model.ModelUtils

import java.time.Instant
import scala.jdk.DurationConverters.*
import scala.reflect.ClassTag

object AwaitBuilder {

  trait Step0[Ctx <: WorkflowContext]() {

    def await[In <: WCState[Ctx]](duration: java.time.Duration): AwaitBuilderStep1[In]                       = AwaitBuilderStep1(DurationSource.Static(duration))
    def await[In <: WCState[Ctx]](duration: scala.concurrent.duration.FiniteDuration): AwaitBuilderStep1[In] = AwaitBuilderStep1(
      DurationSource.Static(duration.toJava),
    )

    case class AwaitBuilderStep1[InOut <: WCState[Ctx]](private val durationSource: DurationSource[InOut]) {

      // raw variant
//      def persistThrough(incorporate: WIO.Timer.Started => WCEvent[Ctx], detect: WCEvent[Ctx] => Option[WIO.Timer.Started]): Step2 = {
//        val evtHanlder: EventHandler[InOut, Unit, WCEvent[Ctx], Timer.Started] = EventHandler(
//          detect,
//          incorporate,
//          (_, _) => (),
//        )
//        Step2(evtHanlder)
//      }

      def persistThrough[Evt <: WCEvent[Ctx]](
          incorporate: WIO.Timer.Started => Evt,
      )(extractStartTime: Evt => Instant)(using ct: ClassTag[Evt]): Step2 = {
        val evtHanlder: EventHandler[InOut, Unit, WCEvent[Ctx], Timer.Started] = EventHandler(
          ct.unapply.andThen(_.map(x => Timer.Started(extractStartTime(x)))),
          incorporate,
          (_, _) => (),
        )
        Step2(evtHanlder)
      }

      case class Step2(private val eventHandler: EventHandler[InOut, Unit, WCEvent[Ctx], Timer.Started], private val name: Option[String] = None) {
        def named(timerName: String): Step2               = this.copy(name = Some(timerName))
        def autoNamed(using name: sourcecode.Name): Step2 = this.copy(name = Some(ModelUtils.prettifyName(name.value)))
        def done: WIO[InOut, Nothing, InOut, Ctx]         = WIO.Timer(durationSource, eventHandler, x => Right(x), name)
      }

    }

  }

}
