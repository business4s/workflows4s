package workflows4s.utils

import java.security.SecureRandom
import scala.util.Random
import java.nio.ByteBuffer
import java.security.MessageDigest

object StringUtils {

  private val random                           = new SecureRandom()
  def randomAlphanumericString(n: Int): String = Random(random).alphanumeric.take(n).mkString

  def stringToLong(s: String): Long = {
    if s == null
    then stringToLong("")
    else {
      val md    = MessageDigest.getInstance("SHA-256")
      val bytes = md.digest(s.getBytes("UTF-8"))
      ByteBuffer.wrap(bytes.take(8)).getLong
    }
  }
}
