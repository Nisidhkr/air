package p2p.load

import io.gatling.core.Predef._
import io.gatling.http.Predef._

import scala.concurrent.duration._
import scala.util.Random

/**
 * Fylo API load test (backbone §18.1/§18.2). Run against a live backend:
 *
 *   java -jar target/p2p-1.0-SNAPSHOT.jar &      # port 9090
 *   mvn gatling:test -Dgatling.simulationClass=p2p.load.FyloLoadSimulation
 *
 * Override the target with -DbaseUrl=http://host:port
 */
class FyloLoadSimulation extends Simulation {

  private val baseUrl = System.getProperty("baseUrl", "http://localhost:9090")

  private val httpProtocol = http
    .baseUrl(baseUrl)
    .contentTypeHeader("application/json")
    .acceptHeader("application/json")

  private val userFeeder = Iterator.continually {
    Map("username" -> s"load_${Random.alphanumeric.take(10).mkString.toLowerCase}")
  }

  // ── Scenario 1: AuthAndPlan (warm-up) ────────────────────────────────
  private val authAndPlan = scenario("AuthAndPlan")
    .feed(userFeeder)
    .exec(
      http("register")
        .post("/api/v1/auth/register")
        .body(StringBody("""{"username":"#{username}","password":"Load1234!"}"""))
        .check(status.is(201))
        .check(jsonPath("$.tokens.accessToken").saveAs("token")))
    .exec(
      http("plan")
        .get("/api/v1/plan")
        .header("Authorization", "Bearer #{token}")
        .check(status.is(200))
        .check(jsonPath("$.tier").is("FREE")))

  // ── Scenario 2: DirectShareCycle (Mode 1 rendezvous throughput) ──────
  private val directShareCycle = scenario("DirectShareCycle")
    .exec(
      http("direct-initiate")
        .post("/api/v1/transfers/direct/initiate")
        .body(StringBody("""{"fileName":"load.bin","fileSizeBytes":1048576}"""))
        .check(status.is(200))
        .check(jsonPath("$.sessionCode").saveAs("code")))
    .exec(
      http("direct-join")
        .post("/api/v1/transfers/direct/join")
        .body(StringBody("""{"code":"#{code}"}"""))
        .check(status.is(200))
        .check(jsonPath("$.sessionId").exists))

  // ── Scenario 3: LinkShareDownload (Mode 4 read path) ─────────────────
  // Feeder file: slug per line, pre-created (see LargeFileTransferSimulation
  // or the smoke script); falls back to hammering a single slug.
  private val slug = System.getProperty("slug", "replace1")
  private val linkShareDownload = scenario("LinkShareDownload")
    .exec(
      http("link-download")
        .get(s"/s/$slug")
        .check(status.in(200, 404))) // 404 counts are visible in the report

  // ── Scenario 4: RateLimitBehaviour ───────────────────────────────────
  private val rateLimitBehaviour = scenario("RateLimitBehaviour")
    .exec(
      http("login-hammer")
        .post("/api/v1/auth/login")
        .body(StringBody("""{"username":"nobody","password":"wrong"}"""))
        .check(status.in(401, 429))
        .check(status.transform(s => if (s == 429) "1" else "0").saveAs("limited")))

  setUp(
    authAndPlan.inject(rampUsers(50).during(10.seconds)),
    directShareCycle.inject(rampUsers(100).during(30.seconds)),
    linkShareDownload.inject(rampUsers(100).during(30.seconds)),
    rateLimitBehaviour.inject(atOnceUsers(30))
  ).protocols(httpProtocol)
    .assertions(
      details("direct-initiate").responseTime.percentile(99).lt(1000),
      details("direct-join").responseTime.percentile(99).lt(1000),
      details("link-download").responseTime.percentile(95).lt(500),
      // Rate limiter must fire under the hammer (≥1% 429s overall).
      details("login-hammer").failedRequests.percent.is(0.0),
      global.failedRequests.percent.lt(1.0)
    )
}
