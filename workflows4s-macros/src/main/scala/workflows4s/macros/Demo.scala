package workflows4s.macros

import cats.effect.IO

/** Demo and exploration of HKT extraction macro capabilities.
  *
  * Run with: sbt "workflows4s-macros/runMain workflows4s.macros.demo"
  */
@main def demo(): Unit = {
  import EffectExtraction.*

  // Test contexts with different effect types
  trait TestContext { type F[_] }

  object OptionCtx extends TestContext { type F[A] = Option[A] }
  object ListCtx extends TestContext { type F[A] = List[A] }
  object IOCtx extends TestContext { type F[A] = IO[A] }

  println("=== HKT Extraction Macro Demo ===\n")

  // Test 1: HasEffect derivation
  println("1. HasEffect derivation (macro runs at compile time):")

  val optHas = summon[HasEffect[OptionCtx.type]]
  println(s"   OptionCtx: $optHas")

  val listHas = summon[HasEffect[ListCtx.type]]
  println(s"   ListCtx: $listHas")

  val ioHas = summon[HasEffect[IOCtx.type]]
  println(s"   IOCtx: $ioHas")

  // Test 2: TypeWitness
  println("\n2. TypeWitness (transparent inline):")

  val optWitness = effectOf[OptionCtx.type]
  println(s"   OptionCtx witness: $optWitness")

  val listWitness = effectOf[ListCtx.type]
  println(s"   ListCtx witness: $listWitness")

  // Test 3: Path-dependent usage
  println("\n3. Path-dependent type usage:")

  def processWithEffect[Ctx <: TestContext](using he: HasEffect[Ctx]): String = {
    // We have he.F available as a type
    // But we can't construct values without knowing concrete F
    s"HasEffect available, F is existential"
  }

  println(s"   ${processWithEffect[OptionCtx.type]}")

  // Test 4: The core challenge - using F in return types
  println("\n4. The core challenge:")
  println("""
   The macro CAN extract F[_] from Ctx at compile time.
   The extracted type IS correct (Option, List, IO, etc.)

   HOWEVER, the compiler treats he.F as existential:
   - he.F[Int] is NOT unified with Option[Int]
   - To unify them, we need HasEffect.Aux[Ctx, Option]
   - Aux requires F as a type parameter - back to explicit F!

   This is a fundamental Scala limitation, not a macro limitation.
   The macro extracts the type correctly, but Scala's type system
   doesn't allow using that extracted type in a unified way.
""")

  // Test 5: What DOES work
  println("5. What works with macros:")
  println("""
   ✓ Extracting F type at compile time
   ✓ Creating instances that "know" F internally
   ✓ Using F in path-dependent signatures (he.F[A])
   ✓ Runtime type inspection

   ✗ Unifying he.F[Int] with Option[Int] without explicit F
   ✗ Using F in type aliases without type projection
   ✗ Removing F from type parameters of classes/traits
""")

  println("\n=== Conclusion ===")
  println("""
   The macro approach works for extraction but doesn't solve
   the fundamental problem of needing F visible at the type level.

   Options:
   1. Accept explicit F parameter (current approach) - cleanest
   2. Use casts internally (unsafe but hides complexity)
   3. Require HasEffect evidence at every call site (verbose)
   4. Wait for Scala to add better HKT extraction support
""")

  // Run cast approach demo
  CastApproach.demo()
}
