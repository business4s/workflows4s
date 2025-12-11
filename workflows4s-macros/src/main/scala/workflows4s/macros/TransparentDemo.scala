package workflows4s.macros

import cats.effect.IO

/** Test if transparent inline preserves type refinement.
  *
  * Run: sbt "workflows4s-macros/runMain workflows4s.macros.transparentDemo"
  */
@main def transparentDemo(): Unit = {
  import TransparentApproach.*

  trait TestCtx { type F[_] }
  object IOCtx extends TestCtx { type F[A] = IO[A] }
  object OptionCtx extends TestCtx { type F[A] = Option[A] }

  println("=== Transparent Inline Macro Test ===\n")

  // Get the HasEffect instance
  val heIO = summon[HasEffect[IOCtx.type]]
  val heOpt = summon[HasEffect[OptionCtx.type]]

  println(s"IO HasEffect: $heIO")
  println(s"Option HasEffect: $heOpt")

  // THE KEY TEST: Does the compiler preserve the type refinement?
  // If transparent inline works, these should compile WITHOUT cast:

  val ioValue: heIO.F[Int] = IO.pure(42)
  val optValue: heOpt.F[Int] = Some(42)

  println(s"\nIO value (no cast): $ioValue")
  println(s"Option value (no cast): $optValue")

  println("\n✓ Transparent inline DOES preserve type refinement!")
}
