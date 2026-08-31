package p2p.load

import io.gatling.core.Predef._
import io.gatling.http.Predef._

import scala.concurrent.duration._

/**
 * Large-file stress (backbone "40 GB under 100 MB RAM"). Uploads a 1 GB
 * file through the link-share path in one streamed multipart request
 * (the server spools it with constant memory), downloads it back via
 * /s/{slug}, and watches the heap gauge for runaway growth.
 *
 *   1. Register a user and export TOKEN
 *   2. head -c 1073741824 /dev/urandom > /tmp/gatling-1g.bin
 *   3. mvn gatling:test \
 *        -Dgatling.simulationClass=p2p.load.LargeFileTransferSimulation \
 *        -Dtoken=$TOKEN [-DbaseUrl=...] [-Dfile=/tmp/gatling-1g.bin]
 */
class LargeFileTransferSimulation extends Simulation {

  private val baseUrl = System.getProperty("baseUrl", "http://localhost:9090")
  private val token = System.getProperty("token", "missing-token")
  private val filePath = System.getProperty("file", "/tmp/gatling-1g.bin")

  private val httpProtocol = http.baseUrl(baseUrl)

  private val largeTransfer = scenario("LargeFileTransfer")
    .exec(
      http("upload-1g")
        .post("/api/v1/links/upload")
        .header("Authorization", s"Bearer $token")
        .bodyPart(RawFileBodyPart("file", filePath).fileName("gatling-1g.bin"))
        .asMultipartForm
        .check(status.is(201))
        .check(jsonPath("$.slug").saveAs("slug")))
    .exec(
      http("download-1g")
        .get("/s/#{slug}")
        .check(status.is(200)))
    .exec(
      // Heap sanity: the 1 GB round-trip must not have ballooned the heap.
      http("heap-check")
        .get("/metrics")
        .check(status.is(200))
        .check(regex("fylo_jvm_heap_used_bytes (\\d+)").ofType[String].saveAs("heap")))
    .exec { session =>
      val heap = session("heap").as[String].toLong
      println(s"JVM heap after 1 GB round-trip: ${heap / 1024 / 1024} MB")
      session
    }

  setUp(largeTransfer.inject(atOnceUsers(1)))
    .protocols(httpProtocol)
    .assertions(
      details("download-1g").responseTime.max.lt(30000),
      global.failedRequests.count.is(0L)
    )
}
