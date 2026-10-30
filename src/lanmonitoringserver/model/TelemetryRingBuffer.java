package lanmonitoringserver.model;

import java.util.ArrayList;
import java.util.List;

/** Thread-safe fixed-capacity buffer; old telemetry is discarded deterministically. */
public final class TelemetryRingBuffer {
    private final TelemetrySample[] values;
    private int start;
    private int size;

    public TelemetryRingBuffer(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        values = new TelemetrySample[capacity];
    }

    public synchronized void add(TelemetrySample sample) {
        if (sample == null) return;
        int index = (start + size) % values.length;
        if (size == values.length) {
            values[start] = sample;
            start = (start + 1) % values.length;
        } else {
            values[index] = sample;
            size++;
        }
    }

    public synchronized List<TelemetrySample> snapshot() {
        List<TelemetrySample> result = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            result.add(values[(start + index) % values.length]);
        }
        return List.copyOf(result);
    }

    public synchronized int size() { return size; }
    public synchronized void clear() {
        java.util.Arrays.fill(values, null);
        start = 0;
        size = 0;
    }
    public int capacity() { return values.length; }
}
