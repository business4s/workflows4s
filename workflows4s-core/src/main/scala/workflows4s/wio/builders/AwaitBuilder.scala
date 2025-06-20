package workflows4s.wio.builders

import java.time.Instant

import scala.jdk.DurationConverters.*
import scala.reflect.ClassTag

import workflows4s.wio.*
import workflows4s.wio.WIO.Timer
import workflows4s.wio.WIO.Timer.DurationSource
import workflows4s.wio.internal.EventHandler
import workflows4s.wio.model.ModelUtils

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

      def persistStartThrough[Evt <: WCEvent[Ctx]](
          incorporate: WIO.Timer.Started => Evt,
      )(extractStartTime: Evt => Instant)(using ct: ClassTag[Evt]): Step2 = {
        val evtHanlder: EventHandler[InOut, Unit, WCEvent[Ctx], Timer.Started] = EventHandler(
          ct.unapply.andThen(_.map(x => Timer.Started(extractStartTime(x)))),
          incorporate,
          (_, _) => (),
        )
        Step2(evtHanlder)
      }

      case class Step2(private val startedEventHandler: EventHandler[InOut, Unit, WCEvent[Ctx], Timer.Started]) {

        def persistReleaseThrough[Evt <: WCEvent[Ctx]](
            incorporate: WIO.Timer.Released => Evt,
        )(extractReleaseTime: Evt => Instant)(using ct: ClassTag[Evt]): Step3 = {
          val evtHanlder: EventHandler[InOut, Either[Nothing, InOut], WCEvent[Ctx], Timer.Released] = EventHandler(
            ct.unapply.andThen(_.map(x => Timer.Released(extractReleaseTime(x)))),
            incorporate,
            (in, _) => Right(in),
          )
          Step3(evtHanlder)
        }

        case class Step3(
            private val releasedEventHandler: EventHandler[InOut, Either[Nothing, InOut], WCEvent[Ctx], Timer.Released],
            private val name: Option[String] = None,
        ) {

          def named(timerName: String): WIO[InOut, Nothing, InOut, Ctx] = this.copy(name = Some(timerName)).done

          def autoNamed(using name: sourcecode.Name): WIO[InOut, Nothing, InOut, Ctx] =
            this.copy(name = Some(ModelUtils.prettifyName(name.value))).done

          def done: Timer[Ctx, InOut, Nothing, InOut] = WIO.Timer(durationSource, startedEventHandler, name, releasedEventHandler)
        }
      }

    }

  }

}
