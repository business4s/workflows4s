//package workflows4s.wio
//
//// Minimal reproduction: tracked dependent types + inheritance + pattern matching
////
//// Problem: When pattern matching on a subclass, the compiler treats
//// Sub.ctx and Base.ctx as distinct paths, even though Sub.ctx overrides Base.ctx.
////
//// Error:
////   Found:    sub.ctx.Effect[Int]   (ctx in class Sub)
////   Required: base.ctx.Effect[Int]  (ctx in class Base)
//
//trait Ctx {
//  type Effect[T]
//}
//
//sealed class Base(tracked val ctx: Ctx)
//
//object Base {
//  class Sub(tracked override val ctx: Ctx) extends Base(ctx) {
//    def effect: ctx.Effect[Int] = ???
//  }
//}
//
//class Evaluator {
//  def run(base: Base): base.ctx.Effect[Int] = {
//    base match {
//      case sub: (Base.Sub & base.type) => sub.effect
//      // ^ Error: compiler doesn't unify sub.ctx with base.ctx
//    }
//  }
//}
