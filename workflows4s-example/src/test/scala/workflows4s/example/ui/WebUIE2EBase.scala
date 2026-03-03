package workflows4s.example.ui

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.openqa.selenium.WebDriver
import org.openqa.selenium.chrome.{ChromeDriver, ChromeOptions}
import org.scalatest.concurrent.Eventually
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, Tag}
import workflows4s.example.api.BaseServer

import scala.compiletime.uninitialized
import scala.util.Try

object E2E extends Tag("E2E")

/** Shared infrastructure for Web UI E2E tests. Starts an embedded server and manages a headless Chrome WebDriver per test.
  *
  * Prerequisites:
  *   - Chrome/Chromium browser must be installed
  *   - ChromeDriver must be installed and available in PATH, or its location specified via system property
  *
  * To skip these tests: sbt "testOnly * -- -l E2E"
  *
  * To run only these tests: sbt "testOnly * -- -n E2E"
  */
trait WebUIE2EBase extends AnyFreeSpec with Matchers with BeforeAndAfterAll with BeforeAndAfterEach with Eventually with BaseServer {

  protected def serverPort: Int
  protected lazy val baseUrl: String = s"http://localhost:$serverPort"

  protected var driver: WebDriver                                           = uninitialized
  private var serverFiber: cats.effect.kernel.Fiber[IO, Throwable, Nothing] = uninitialized

  override def beforeAll(): Unit = {
    super.beforeAll()
    val serverIO = serverWithUi(serverPort, baseUrl).use { server =>
      IO.println(s"Test server started at http://${server.address}") *>
        IO.never
    }
    serverFiber = serverIO.start.unsafeRunSync()
    // wait for server to be ready
    Thread.sleep(3000)
  }

  override def afterAll(): Unit = {
    Try(serverFiber.cancel.unsafeRunSync())
    super.afterAll()
  }

  override def beforeEach(): Unit = {
    super.beforeEach()
    val options = new ChromeOptions()
    options.addArguments("--headless=new")
    options.addArguments("--no-sandbox")
    options.addArguments("--disable-dev-shm-usage")
    options.addArguments("--disable-gpu")
    options.addArguments("--window-size=1920,1080")
    driver = new ChromeDriver(options)
    driver.manage().timeouts().implicitlyWait(java.time.Duration.ofSeconds(10)): Unit
  }

  override def afterEach(): Unit = {
    Try(driver.quit())
    super.afterEach()
  }

  /** Navigate to a workflow's Instances tab. */
  protected def navigateToInstancesTab(): Unit = {
    import org.openqa.selenium.By
    import org.scalatest.time.{Seconds, Span}
    import scala.jdk.CollectionConverters.*

    driver.get(s"$baseUrl/ui/")

    eventually(timeout(Span(15, Seconds))) {
      val workflowLinks = driver.findElements(By.cssSelector("ul.menu-list li a"))
      workflowLinks should not be empty
      workflowLinks.get(0).click()
    }

    eventually(timeout(Span(15, Seconds))) {
      val tabs         = driver.findElements(By.cssSelector("a")).asScala
      val instancesTab = tabs.find(_.getText == "Instances")
      instancesTab shouldBe defined
      instancesTab.get.click()
    }
  }
}
