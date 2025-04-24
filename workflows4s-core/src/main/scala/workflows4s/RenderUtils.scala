package workflows4s

import java.time.Duration

private [workflows4s] object RenderUtils {
  
  def humanReadableDuration(duration: Duration): String = duration.toString.substring(2).replaceAll("(\\d[HMS])(?!$)", "$1 ").toLowerCase

}
