//> using scala 3.7.3

object Builder {
  trait Step0[F[_]] {
    class Inner {
      def build: F[Int] = ???
    }
    def start = new Inner
  }
}

trait Combined[F[_]] extends Builder.Step0[F]

object Test {
  val x = new Combined[[X] =>> X] {}
  val y = x.start.build
}
