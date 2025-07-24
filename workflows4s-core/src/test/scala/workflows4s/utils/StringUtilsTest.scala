package workflows4s.utils

import org.scalatest.EitherValues
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class StringUtilsTest extends AnyFreeSpec with Matchers with EitherValues {

  "StringUtils" - {

    "transform String to Long " in {
      StringUtils.stringToLong("") shouldBe -2039914840885289964L
      StringUtils.stringToLong("test") shouldBe -6951639720043709083L
      StringUtils.stringToLong("wio-129879127918729-sdfkhjskdfjhskdfjhkdb") shouldBe 8350242105965875830L
    }

    "stringToLong handle null String as empty String " in {
      StringUtils.stringToLong(null) shouldBe StringUtils.stringToLong("")
    }

    "stringToLong is consistant " in {
      val values = List(
        "",
        "test",
        "wio-129879127918729-sdfkhjskdfjhskdfjhskdf",
        StringUtils.randomAlphanumericString(4),
        StringUtils.randomAlphanumericString(8),
        StringUtils.randomAlphanumericString(20),
        StringUtils.randomAlphanumericString(100),
        StringUtils.randomAlphanumericString(1000),
      )

      values.map(v => StringUtils.stringToLong(v) shouldBe StringUtils.stringToLong(v))
    }
  }
}
