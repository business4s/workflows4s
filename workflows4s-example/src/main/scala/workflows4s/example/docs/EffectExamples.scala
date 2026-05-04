package workflows4s.example.docs

import scala.annotation.nowarn

@nowarn("msg=unused")
object EffectExamples {

  // effect_context_start
  import workflows4s.wio.WorkflowContext

  object MyCtx extends WorkflowContext {
    type Effect[T] = cats.effect.IO[T] // or Future[T], Try[T], etc.
    type State     = MyState
    type Event     = MyEvent
  }
  // effect_context_end
  trait MyState
  trait MyEvent

  // effect_same_start
  import cats.effect.IO
  import workflows4s.runtime.instanceengine.WorkflowInstanceEngine

  // Context uses IO, engine uses IO — WCEffectLift is derived automatically
  val engine = WorkflowInstanceEngine.basic[IO, MyCtx.Ctx]()
  // effect_same_end

  // effect_mapk_start
  import scala.concurrent.Future
  import cats.effect.unsafe.implicits.global
  import scala.concurrent.ExecutionContext.Implicits.global as ec

  // Build engine in your workflow's effect type, then transform for the runtime
  val ioEngine: WorkflowInstanceEngine[IO, MyCtx.Ctx]         = WorkflowInstanceEngine.basic[IO, MyCtx.Ctx]()
  val futureEngine: WorkflowInstanceEngine[Future, MyCtx.Ctx] =
    ioEngine.mapK([A] => (fa: IO[A]) => fa.unsafeToFuture())
  // effect_mapk_end

  // effect_io_start
  object IOExample {
    import cats.effect.IO
    import workflows4s.wio.WorkflowContext
    import workflows4s.runtime.cats.effect.InMemoryConcurrentRuntime
    import workflows4s.runtime.instanceengine.WorkflowInstanceEngine

    object Ctx extends WorkflowContext {
      type Effect[T] = IO[T]
      type State     = String
      sealed trait Event
      case class Greeting(msg: String) extends Event
    }
    import Ctx.*

    val greet: WIO[Any, Nothing, String] =
      WIO
        .runIO[Any](_ => IO(Greeting("Hello from IO!")))
        .handleEvent((_, evt) => evt.msg)
        .autoNamed()

    val engine  = WorkflowInstanceEngine.basic[IO, Ctx.Ctx]()
    val runtime = InMemoryConcurrentRuntime.default[IO, Ctx.Ctx](greet, "", engine)
    // runtime: IO[InMemoryConcurrentRuntime[IO, Ctx]]
  }
  // effect_io_end

  // effect_try_start
  object TryExample {
    import scala.util.Try
    import workflows4s.wio.WorkflowContext
    import workflows4s.runtime.InMemorySynchronizedRuntime
    import workflows4s.runtime.instanceengine.WorkflowInstanceEngine

    object Ctx extends WorkflowContext {
      type Effect[T] = Try[T]
      type State     = String
      sealed trait Event
      case class Greeting(msg: String) extends Event
    }
    import Ctx.*

    val greet: WIO[Any, Nothing, String] =
      WIO
        .runIO[Any](_ => Try(Greeting("Hello from Try!")))
        .handleEvent((_, evt) => evt.msg)
        .autoNamed()

    val engine   = WorkflowInstanceEngine.basic[Try, Ctx.Ctx]()
    val runtime  = InMemorySynchronizedRuntime.create[Try, Ctx.Ctx](greet, "", engine)
    val instance = runtime.createInstance("id")
    // instance: Try[InMemorySynchronizedWorkflowInstance[Try, Ctx]]
  }
  // effect_try_end

  // effect_zio_start
  object ZIOExample {
    import zio.{Task, ZIO}
    import zio.interop.catz.*
    import workflows4s.wio.WorkflowContext
    import workflows4s.runtime.InMemorySynchronizedRuntime
    import workflows4s.runtime.instanceengine.WorkflowInstanceEngine

    object Ctx extends WorkflowContext {
      type Effect[T] = Task[T]
      type State     = String
      sealed trait Event
      case class Greeting(msg: String) extends Event
    }
    import Ctx.*

    val greet: WIO[Any, Nothing, String] =
      WIO
        .runIO[Any](_ => ZIO.attempt(Greeting("Hello from ZIO!")))
        .handleEvent((_, evt) => evt.msg)
        .autoNamed()

    val engine  = WorkflowInstanceEngine.basic[Task, Ctx.Ctx]()
    val runtime = InMemorySynchronizedRuntime.create[Task, Ctx.Ctx](greet, "", engine)
    // runtime: InMemorySynchronizedRuntime[Task, Ctx]
  }
  // effect_zio_end

  // effect_future_start
  object FutureExample {
    import scala.concurrent.Future
    import scala.concurrent.ExecutionContext.Implicits.global as ec
    import workflows4s.wio.WorkflowContext
    import workflows4s.runtime.pekko.PekkoRuntime
    import workflows4s.runtime.instanceengine.WorkflowInstanceEngine
    import org.apache.pekko.actor.typed.ActorSystem

    object Ctx extends WorkflowContext {
      type Effect[T] = Future[T]
      type State     = String
      sealed trait Event
      case class Greeting(msg: String) extends Event
    }
    import Ctx.*

    val greet: WIO[Any, Nothing, String] =
      WIO
        .runIO[Any](_ => Future.successful(Greeting("Hello from Future!")))
        .handleEvent((_, evt) => evt.msg)
        .autoNamed()

    given ActorSystem[?]                                = ???
    val engine: WorkflowInstanceEngine[Future, Ctx.Ctx] = ???
    val runtime: PekkoRuntime[Ctx.Ctx]                  = PekkoRuntime.create[Ctx.Ctx]("greeting", greet, "", engine)
    // runtime: PekkoRuntime[Ctx] — backed by Pekko Persistence
  }
  // effect_future_end

}
