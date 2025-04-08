package workflows4s.wio

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

// A simple case class to mimic the behavior of WIO.Pure
case class SimplePure[In, Out](value: In => Either[String, Out], name: Option[String])

class WIOSpec extends AnyFunSuite with Matchers {

  test("SimplePure should return the expected output") {
    // Create a SimplePure instance
    val simplePure = SimplePure[Int, String](
      value = input => if (input > 0) Right(s"Input: $input") else Left("Input must be positive"),
      name = Some("TestSimplePure")
    )

    // Test the value function with a positive input
    val positiveInput = 42
    val positiveResult = simplePure.value(positiveInput)
    positiveResult shouldBe Right("Input: 42")

    // Test the value function with a non-positive input
    val negativeInput = -1
    val negativeResult = simplePure.value(negativeInput)
    negativeResult shouldBe Left("Input must be positive")
  }
}