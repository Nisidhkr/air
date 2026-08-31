package p2p.device;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Actively verifies liveness of known devices by pinging their
 * {@code GET /lan/ping} endpoint. mDNS departure events can be lost (device
 * sleep, abrupt Wi-Fi drop), so presence is confirmed end-to-end: a device is
 * "online" only if its API actually answers.
 */
public final class HeartbeatService {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    /** @return true if the device answered the ping with its own deviceId. */
    public boolean ping(DeviceInfo device) {
        if (device.host() == null || device.host().isBlank() || device.apiPort() <= 0) {
            return false;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://" + device.host() + ":" + device.apiPort() + "/lan/ping"))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 && response.body().contains(device.deviceId());
        } catch (Exception e) {
            return false;
        }
    }
}
