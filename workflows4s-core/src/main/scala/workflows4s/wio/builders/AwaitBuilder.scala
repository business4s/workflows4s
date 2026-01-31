package workflows4s.wio.builders

import java.time.Instant

import scala.jdk.DurationConverters.*
import scala.reflect.ClassTag

import workflows4s.wio.*
import workflows4s.wio.WIO.Timer
import workflows4s.wio.WIO.Timer.DurationSource
import workflows4s.wio.internal.EventHandler
import workflows4s.wio.model.ModelUtils

trait AwaitBuilderStep0[F[_], Ctx <: WorkflowContext] {

  def await[In <: WCState[Ctx]](duration: java.time.Duration): AwaitBuilderStep1[In]                       = AwaitBuilderStep1(DurationSource.Static(duration))
  def await[In <: WCState[Ctx]](duration: scala.concurrent.duration.FiniteDuration): AwaitBuilderStep1[In] = AwaitBuilderStep1(
    DurationSource.Static(duration.toJava),
  )

  case class AwaitBuilderStep1[InOut <: WCState[Ctx]](private val durationSource: DurationSource[InOut]) {

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
        val evtHandler: EventHandler[InOut, Either[Nothing, InOut], WCEvent[Ctx], Timer.Released] = EventHandler(
          ct.unapply.andThen(_.map(x => Timer.Released(extractReleaseTime(x)))),
          incorporate,
          (in, _) => Right(in),
        )
        Step3(evtHandler)
      }

      case class Step3(
          private val releasedEventHandler: EventHandler[InOut, Either[Nothing, InOut], WCEvent[Ctx], Timer.Released],
          private val name: Option[String] = None,
      ) {

        def named(timerName: String): WIO.Timer[F, Ctx, InOut, Nothing, InOut] = this.copy(name = Some(timerName)).done

        def autoNamed(using name: sourcecode.Name): WIO.Timer[F, Ctx, InOut, Nothing, InOut] =
          this.copy(name = Some(ModelUtils.prettifyName(name.value))).done

        def done: WIO.Timer[F, Ctx, InOut, Nothing, InOut] = WIO.Timer(durationSource, startedEventHandler, name, releasedEventHandler)
      }
    }

  }

}

object AwaitBuilder {
  type Step0[F[_], Ctx <: WorkflowContext] = AwaitBuilderStep0[F, Ctx]
}
