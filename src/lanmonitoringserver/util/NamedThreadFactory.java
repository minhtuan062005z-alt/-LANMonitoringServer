package lanmonitoringserver.util;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public final class NamedThreadFactory implements ThreadFactory {
    private final String prefix;
    private final AtomicInteger sequence = new AtomicInteger();
    private final boolean daemon;

    public NamedThreadFactory(String prefix, boolean daemon) {
        this.prefix = prefix;
        this.daemon = daemon;
    }

    @Override
    public Thread newThread(Runnable runnable) {
        Thread thread = new Thread(runnable, prefix + "-" + sequence.incrementAndGet());
        thread.setDaemon(daemon);
        thread.setUncaughtExceptionHandler((ignored, error) -> error.printStackTrace(System.err));
        return thread;
    }
}
