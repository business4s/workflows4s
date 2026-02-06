package workflows4s.wio

// =============================================================================
// PROOF OF CONCEPT: Scala 3 Modularity for WIO
// =============================================================================
//
// This experiment explores using Scala 3's experimental modularity features
// (tracked parameters, applied constructor types) to eliminate the Ctx type
// parameter from WIO.
//
// Current WIO: WIO[In, Err, Out, Ctx]  - Ctx is a type parameter
// New WIO2:    WIO2[In, Err](ctx)      - ctx is a tracked value parameter
//
// Benefits of the new approach:
// - More natural dependent typing (Out <: ctx.State)
// - Better integration with modular programming patterns
// - Potentially simpler type signatures for users
//
// =============================================================================

trait WorkflowContext2 {
  type Event
  type State

  // User-facing type alias - preserves current API style
  // Users write: ctx.WIO[Int, String, MyState] instead of WIO[Int, String, MyState, Ctx]
  type WIO[In, Err, Out_ <: State] = WIO2[In, Err](this) { type Out = Out_ }
}

// The core sealed trait with tracked ctx parameter
// The `tracked` modifier allows dependent types like `Out <: ctx.State`
sealed trait WIO2[-In, +Err](tracked val ctx: WorkflowContext2) {
  type Out <: ctx.State
}

object WIO2 {

  // ===========================================================================
  // BASIC VARIANTS - These work well with tracked ctx
  // ===========================================================================

  abstract class Pure[-In, +Err](tracked override val ctx: WorkflowContext2) extends WIO2[In, Err](ctx) {
    def value: In => Either[Err, Out]
  }

  abstract class RunIO[-In, +Err, Evt](tracked override val ctx: WorkflowContext2) extends WIO2[In, Err](ctx) {
    def buildIO: In => cats.effect.IO[Evt]
    def convertEvent: Evt => ctx.Event  // ctx.Event works here
  }

  class End(tracked override val ctx: WorkflowContext2) extends WIO2[Any, Nothing](ctx) {
    type Out = Nothing
  }

  // ===========================================================================
  // COMPOSITION VARIANTS - Need workarounds due to compiler limitations
  // ===========================================================================

  // FlatMap: COMPILER CRASH when writing: `def getNext: Out1 => WIO2[Out1, Err](ctx)`
  // The crash occurs in checkUnusedPostTyper phase when a function returns
  // WIO2 with a type member (Out1) as a type argument.
  //
  // WORKAROUND: Use `getNextUnsafe: Out1 => Any` and cast at use site
  abstract class FlatMap[-In, +Err](
    tracked override val ctx: WorkflowContext2
  ) extends WIO2[In, Err](ctx) {
    type Out1 <: ctx.State
    def base: WIO2[In, Err](ctx) { type Out = Out1 }
    // The actual type is: Out1 => WIO2[Out1, Err](ctx) { type Out <: ctx.State }
    // But we can't express it without crashing the compiler
    def getNextUnsafe: Out1 => Any
  }

  // AndThen: Works because we store WIO values, not functions returning WIO
  abstract class AndThen[-In, +Err](
    tracked override val ctx: WorkflowContext2
  ) extends WIO2[In, Err](ctx) {
    type Out1 <: ctx.State
    def first: WIO2[In, Err](ctx) { type Out = Out1 }
    def second: WIO2[Out1, Err](ctx)
  }

  // Transform: Works - no function returning WIO2 needed
  abstract class Transform[In1, -In2, +Err2](
    tracked override val ctx: WorkflowContext2
  ) extends WIO2[In2, Err2](ctx) {
    type Err1
    type Out1 <: ctx.State
    def base: WIO2[In1, Err1](ctx) { type Out = Out1 }
    def contramapInput: In2 => In1
    def mapOutput: (In2, Either[Err1, Out1]) => Either[Err2, Out]
  }

  // ===========================================================================
  // BUILDERS - Provide type-safe construction
  // ===========================================================================

  class PureBuilder(tracked val ctx: WorkflowContext2) {
    def apply[In, Err, Out_ <: ctx.State](f: In => Either[Err, Out_]): WIO2[In, Err](ctx) { type Out = Out_ } =
      new Pure[In, Err](ctx) {
        type Out = Out_
        override def value: In => Either[Err, Out] = f
      }
  }

  class AndThenBuilder(tracked val ctx: WorkflowContext2) {
    def apply[In, Err, Out1_ <: ctx.State, Out2_ <: ctx.State](
      first_ : WIO2[In, Err](ctx) { type Out = Out1_ },
      second_ : WIO2[Out1_, Err](ctx) { type Out = Out2_ }
    ): WIO2[In, Err](ctx) { type Out = Out2_ } =
      new AndThen[In, Err](ctx) {
        type Out1 = Out1_
        type Out = Out2_
        def first: WIO2[In, Err](ctx) { type Out = Out1_ } = first_
        def second: WIO2[Out1_, Err](ctx) = second_
      }
  }
}

// =============================================================================
// TEST CONTEXT
// =============================================================================
object TestCtx extends WorkflowContext2 {
  type Event = String
  type State = Int
}

// =============================================================================
// PROOF: Building WIOs
// =============================================================================
object BuildTest {
  val pureBuilder = WIO2.PureBuilder(TestCtx)
  val andThenBuilder = WIO2.AndThenBuilder(TestCtx)

  val wio1: TestCtx.WIO[Int, String, Int] = pureBuilder[Int, String, Int](i => Right(i + 1))
  val wio2: TestCtx.WIO[Int, String, Int] = pureBuilder[Int, String, Int](i => Right(i * 2))

  // Composition works!
  val composed: TestCtx.WIO[Int, String, Int] = andThenBuilder(wio1, wio2)
}

// =============================================================================
// PROOF: Visitor pattern (non ctx-dependent return)
// =============================================================================
object VisitorNonDependentReturn {
  def countNodes(wio: WIO2[?, ?]): Int = {
    wio match {
      case _: WIO2.Pure[?, ?] => 1
      case _: WIO2.RunIO[?, ?, ?] => 1
      case _: WIO2.End => 1
      case w: WIO2.AndThen[?, ?] =>
        countNodes(w.first) + countNodes(w.second)
      case w: WIO2.FlatMap[?, ?] =>
        countNodes(w.base) + 1
      case w: WIO2.Transform[?, ?, ?] =>
        countNodes(w.base) + 1
    }
  }

  val count: Int = countNodes(BuildTest.composed)  // = 2
}

// =============================================================================
// PROOF: Visitor pattern (ctx-dependent return)
// =============================================================================
object VisitorCtxDependentReturn {
  // When return type depends on ctx, we need the `& wio.type` trick
  def evalPure[In, Err](wio: WIO2[In, Err], in: In): Option[Either[Err, wio.Out]] = {
    wio match {
      case w: (WIO2.Pure[In, Err] & wio.type) =>
        Some(w.value(in))
      case _ => None
    }
  }

  val result: Option[Either[String, Int]] = evalPure(BuildTest.wio1, 5)  // Some(Right(6))
}

// =============================================================================
// PROOF: Recursive visitor with ctx-dependent return
// =============================================================================
object RecursiveVisitor {
  def eval[In, Err](wio: WIO2[In, Err], in: In): Option[Either[Err, wio.Out]] = {
    wio match {
      case w: (WIO2.Pure[In, Err] & wio.type) =>
        Some(w.value(in))

      case w: (WIO2.AndThen[In, Err] & wio.type) =>
        val firstResult = eval(w.first, in)
        firstResult.flatMap {
          case Left(err) => Some(Left(err))
          case Right(out1) =>
            val secondResult = eval(w.second, out1)
            // Need cast: compiler can't prove w.second.Out =:= wio.Out
            secondResult.asInstanceOf[Option[Either[Err, wio.Out]]]
        }

      case _ => None
    }
  }
}

// =============================================================================
// SUMMARY OF FINDINGS
// =============================================================================
/*

✅ WHAT WORKS:
   - Basic WIO2 sealed trait with tracked ctx
   - Simple variants (Pure, RunIO, End)
   - Type aliases in WorkflowContext2 for user API
   - Builders with tracked ctx
   - Pattern matching with `& wio.type` for ctx-dependent returns
   - Storing WIO values in composition (AndThen, Transform)
   - Non-ctx-dependent visitors (like countNodes)

❌ COMPILER CRASH (Scala 3.7.4):
   - `def getNext: Out1 => WIO2[Out1, Err](ctx)` where Out1 is a type member
   - This is the function signature needed for FlatMap's lazy continuation
   - Error: "type of AppliedTypeTree(...) is not assigned" in checkUnusedPostTyper
   - WORKAROUND: `def getNextUnsafe: Out1 => Any` with casts at use site

⚠️  REQUIRES CASTS:
   - Recursive evaluation with ctx-dependent return types
   - Compiler can't prove type equality between nested WIO's Out types

📋 MIGRATION FEASIBILITY:
   - POSSIBLE with acceptable compromises:
     1. FlatMap needs getNextUnsafe workaround (cast localized in evaluators)
     2. Recursive visitors need asInstanceOf for some cases
     3. User-facing API remains type-safe through builders

📋 RECOMMENDED MIGRATION APPROACH:
   1. Keep WIO and WIO2 in parallel during migration
   2. Implement simple variants first (Pure, RunIO, End, Transform, AndThen)
   3. Add FlatMap with getNextUnsafe workaround
   4. Create conversion functions between WIO and WIO2
   5. Migrate evaluators one by one
   6. Test cross-context variants (Embedded, ForEach) separately
   7. Eventually deprecate WIO in favor of WIO2

🐛 KNOWN BLOCKERS:
   1. Compiler crash needs to be reported to Scala team
   2. Cross-context operations (Embedded, ForEach) not yet tested
   3. Full visitor redesign needed for new type structure

*/
