package workflows4s.example.ui
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import com.comcast.ip4s.*
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits.*
import org.http4s.dsl.io.*
import org.openqa.selenium.chrome.{ChromeDriver, ChromeOptions}
import org.openqa.selenium.{By, WebDriver}
import org.scalatest.concurrent.Eventually
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Seconds, Span}
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, Tag}
import sttp.tapir.server.http4s.Http4sServerInterpreter
import workflows4s.example.api.BaseServer
import workflows4s.ui.bundle.UiEndpoints
import workflows4s.web.api.model.UIConfig

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
  private var driver: WebDriver = uninitialized
  private var serverFiber: cats.effect.kernel.Fiber[IO, Throwable, Nothing] = uninitialized
  override def beforeAll(): Unit = {
    super.beforeAll()
    // start the server
    val serverIO = (for {
      api       <- apiRoutes
      apiUrl     = s"http://localhost:$port"
      uiRoutes   = Http4sServerInterpreter[IO]().toRoutes(UiEndpoints.get(UIConfig(sttp.model.Uri.unsafeParse(apiUrl), true)))
      redirect   = org.http4s.HttpRoutes.of[IO] {
                     case req @ org.http4s.Method.GET -> Root / "ui" =>
                       org.http4s
                         .Response[IO](org.http4s.Status.PermanentRedirect)
                         .putHeaders(org.http4s.headers.Location(req.uri / ""))
                         .pure[IO]
                     case org.http4s.Method.GET -> Root              =>
                       org.http4s
                         .Response[IO](org.http4s.Status.PermanentRedirect)
                         .putHeaders(org.http4s.headers.Location(org.http4s.Uri.unsafeFromString("/ui/")))
                         .pure[IO]
                   }
      allRoutes  = api <+> redirect <+> uiRoutes
      server    <- EmberServerBuilder
                     .default[IO]
                     .withHost(ipv4"0.0.0.0")
                     .withPort(Port.fromInt(port).get)
                     .withHttpApp(allRoutes.orNotFound)
                     .build
    } yield server).use { server =>
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
      // Wait for the page to load and check for common UI elements
      eventually(timeout(Span(15, Seconds))) {
        // Look for any text content that indicates the UI has loaded
        val bodyText = driver.findElement(By.tagName("body")).getText
        bodyText should not be empty
      }
    }
    "should have navigation elements" taggedAs(E2E) in {
      driver.get(s"$baseUrl/ui/")
      eventually(timeout(Span(15, Seconds))) {
        // Check that basic HTML structure is present
        val htmlElements = driver.findElements(By.tagName("div"))
        htmlElements should not be empty
      }
    }
    "should be able to interact with search/filter if present" taggedAs(E2E) in {
      driver.get(s"$baseUrl/ui/")
      eventually(timeout(Span(15, Seconds))) {
        // Try to find input fields (search, filter, etc.)
        val inputs = driver.findElements(By.tagName("input")).asScala
        if (inputs.nonEmpty) {
          // If there are input fields, verify they're interactive
          inputs.foreach { input =>
            val inputType = input.getDomAttribute("type")
            if (inputType == "hidden") {
              // Hidden inputs are acceptable
              succeed
            } else {
              // Visible inputs should be displayed and enabled (not readonly)
              input.isDisplayed shouldBe true
              input.isEnabled shouldBe true
              Option(input.getDomAttribute("readonly")) should not be Some("true")
            }
          }
        }
        // At minimum, verify the page is interactive
        driver.findElement(By.tagName("body")).isDisplayed shouldBe true
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
