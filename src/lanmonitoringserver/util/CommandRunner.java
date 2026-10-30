package lanmonitoringserver.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Executes optional OS tools with bounded output and a mandatory timeout. */
public final class CommandRunner {
    public static final int DEFAULT_MAX_OUTPUT_BYTES = 2 * 1024 * 1024;
    private static final ExecutorService READERS = Executors.newFixedThreadPool(8,
            new NamedThreadFactory("command-output", true));

    public CommandResult run(List<String> command, Duration timeout, Charset charset) {
        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(false).start();
        } catch (IOException exception) {
            return new CommandResult(-1, "", exception.getMessage(), false);
        }

        Future<String> stdout = READERS.submit(readStream(process.getInputStream(), charset));
        Future<String> stderr = READERS.submit(readStream(process.getErrorStream(), charset));
        boolean timedOut = false;
        int exitCode = -1;
        try {
            if (process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                exitCode = process.exitValue();
            } else {
                timedOut = true;
                process.destroy();
                if (!process.waitFor(500, TimeUnit.MILLISECONDS)) process.destroyForcibly();
            }
            return new CommandResult(exitCode, getOutput(stdout), getOutput(stderr), timedOut);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return new CommandResult(-1, getOutput(stdout), "Command interrupted", false);
        }
    }

    private Callable<String> readStream(InputStream input, Charset charset) {
        return () -> {
            try (input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int total = 0;
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (total + read > DEFAULT_MAX_OUTPUT_BYTES) {
                        int accepted = DEFAULT_MAX_OUTPUT_BYTES - total;
                        if (accepted > 0) output.write(buffer, 0, accepted);
                        break;
                    }
                    output.write(buffer, 0, read);
                    total += read;
                }
                return output.toString(charset);
            }
        };
    }

    private String getOutput(Future<String> output) {
        try {
            return output.get(1, TimeUnit.SECONDS);
        } catch (Exception exception) {
            output.cancel(true);
            return "";
        }
    }
}
