package workflows4s.example.ui
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.openqa.selenium.chrome.{ChromeDriver, ChromeOptions}
import org.openqa.selenium.{By, WebDriver}
import org.scalatest.concurrent.Eventually
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Seconds, Span}
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, Tag}
import workflows4s.example.api.BaseServer

import scala.compiletime.uninitialized
import scala.jdk.CollectionConverters.*
import scala.util.Try
/**
 * End-to-end tests for the Web UI using Selenium WebDriver.
 * Prerequisites:
 * - Chrome/Chromium browser must be installed
 * - ChromeDriver must be installed and available in PATH, or its location specified via system property
 *
 * To install ChromeDriver on Linux:
 *   sudo apt-get install chromium-chromedriver
 *   For Arch Linux
 *   yay -S chromedriver
 *
 * To install ChromeDriver on macOS:
 *   brew install chromedriver
 *
 * To skip these tests:
 *   sbt "testOnly * -- -l E2E"
 *
 * To run only these tests:
 *   sbt "testOnly * -- -n E2E"
 */
object E2E extends Tag("E2E")
class WebUIE2ETest extends AnyFreeSpec with Matchers with BeforeAndAfterAll with BeforeAndAfterEach with Eventually with BaseServer {
  private val port = 8090
  private val baseUrl = s"http://localhost:$port"
  private val workflowNames = List("Course Registration", "Pull Request", "Withdrawal")
  private var driver: WebDriver = uninitialized
  private var serverFiber: cats.effect.kernel.Fiber[IO, Throwable, Nothing] = uninitialized
  override def beforeAll(): Unit = {
    super.beforeAll()
    // start the server
    val serverIO = serverWithUi(port, baseUrl).use { server =>
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
    // initialize WebDriver with headless Chrome
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
  "Web UI E2E Tests" - {
    "should load the UI home page" taggedAs(E2E) in {
      driver.get(s"$baseUrl/ui/")
      eventually(timeout(Span(10, Seconds))) {
        driver.getTitle should not be empty
      }
      // The page should have loaded successfully
      driver.getCurrentUrl should startWith(s"$baseUrl/ui/")
    }
    "should redirect from root to /ui/" taggedAs(E2E) in {
      driver.get(baseUrl)
      eventually(timeout(Span(10, Seconds))) {
        driver.getCurrentUrl should startWith(s"$baseUrl/ui/")
      }
    }
    "should redirect from /ui to /ui/" taggedAs(E2E) in {
      driver.get(s"$baseUrl/ui")
      eventually(timeout(Span(10, Seconds))) {
        driver.getCurrentUrl should startWith(s"$baseUrl/ui/")
      }
    }
    "should display workflow list" taggedAs(E2E) in {
      driver.get(s"$baseUrl/ui/")
      // Wait for the page to load and verify the workflow list structure
      eventually(timeout(Span(15, Seconds))) {
        // Verify "Available Workflows" label is present (case-insensitive)
        val bodyText = driver.findElement(By.tagName("body")).getText
        bodyText.toUpperCase should include("AVAILABLE WORKFLOWS")

        // Verify all expected workflows are displayed in the list
        workflowNames.foreach(name => bodyText should include(name))

        // Verify the workflow list structure exists (ul.menu-list)
        val menuLists = driver.findElements(By.cssSelector("ul.menu-list"))
        menuLists should not be empty
      }
    }
    "should be able to interact with search/filter if present" taggedAs(E2E) in {
      driver.get(s"$baseUrl/ui/")
      eventually(timeout(Span(15, Seconds))) {
        // First verify that workflow list is loaded
        val bodyText = driver.findElement(By.tagName("body")).getText
        bodyText.toUpperCase should include("AVAILABLE WORKFLOWS")

        // Click on first workflow
        val workflowLinks = driver.findElements(By.cssSelector("ul.menu-list li a"))
        workflowLinks should not be empty
        workflowLinks.get(0).click()
      }

      // Click on "Instances" tab to see filter controls
      eventually(timeout(Span(15, Seconds))) {
        val bodyText = driver.findElement(By.tagName("body")).getText
        bodyText should include("Instances")

        // Find and click the "Instances" tab
        val tabs = driver.findElements(By.cssSelector("a")).asScala
        val instancesTab = tabs.find(_.getText == "Instances")
        instancesTab shouldBe defined
        instancesTab.get.click()
      }

      // Now verify filter bar is present and interactive
      eventually(timeout(Span(15, Seconds))) {
        // Verify select elements (for sorting and page size) are present
        val selects = driver.findElements(By.tagName("select")).asScala
        selects should not be empty
        selects.foreach { select =>
          select.isDisplayed shouldBe true
          select.isEnabled shouldBe true
        }
      }
    }
    "should handle API calls gracefully" taggedAs(E2E) in {
      driver.get(s"$baseUrl/ui/")
      eventually(timeout(Span(15, Seconds))) {
        // Check that there are no JavaScript errors blocking the page
        val logs = driver.manage().logs().get("browser").getAll.asScala
        // Severe errors should not be present
        val severeErrors = logs.filter(_.getLevel.getName == "SEVERE")
        // Assert that there are no severe errors that would prevent the UI from working
        withClue(s"Browser console contained SEVERE errors:\n${severeErrors.map(_.getMessage).mkString("\n")}") {
          severeErrors shouldBe empty
        }
        // The page should be functional
        driver.findElement(By.tagName("body")).isDisplayed shouldBe true
      }
    }
  }
}
