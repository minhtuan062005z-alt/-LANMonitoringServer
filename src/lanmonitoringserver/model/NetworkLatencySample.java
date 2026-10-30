package lanmonitoringserver.model;

/** Network RTT is latency only and is never converted into physical distance. */
public record NetworkLatencySample(long timestamp, MeasurementStatus status,
        Long roundTripMillis, String method) {
    public NetworkLatencySample {
        if (timestamp <= 0 || status == null || roundTripMillis != null && roundTripMillis < 0) {
            throw new IllegalArgumentException("Invalid network latency sample");
        }
        method = method == null ? "PING/PONG application RTT" : method;
    }
}
