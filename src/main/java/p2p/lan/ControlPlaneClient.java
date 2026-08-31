package p2p.lan;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Small JSON-over-HTTP client for device-to-device control messages
 * (offers and offer results). Data never flows through here.
 */
public final class ControlPlaneClient {

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public void postOffer(String host, int apiPort, LanMessages.OfferRequest offer) throws IOException {
        post("http://" + host + ":" + apiPort + "/lan/offer", offer);
    }

    /** Introduces ourselves to a peer and returns its identity. */
    public LanMessages.Hello exchangeHello(String host, int apiPort, LanMessages.Hello self)
            throws IOException {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://" + host + ":" + apiPort + "/lan/hello"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(self)))
                    .build();
            HttpResponse<byte[]> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) {
                throw new IOException("Peer responded HTTP " + response.statusCode());
            }
            return mapper.readValue(response.body(), LanMessages.Hello.class);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while contacting peer", e);
        }
    }

    public void postOfferResult(String host, int apiPort, LanMessages.OfferResult result) {
        try {
            post("http://" + host + ":" + apiPort + "/lan/offer-result", result);
        } catch (IOException e) {
            // Best-effort: the sender may have gone away; the receiver's
            // transfer outcome is already final regardless.
            System.err.println("Could not deliver offer result to " + host + ": " + e.getMessage());
        }
    }

    private void post(String url, Object body) throws IOException {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body)))
                    .build();
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IOException("Peer responded HTTP " + response.statusCode());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while contacting peer", e);
        }
    }
}
