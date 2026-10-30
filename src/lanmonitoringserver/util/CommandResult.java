package lanmonitoringserver.util;

public record CommandResult(int exitCode, String standardOutput,
        String standardError, boolean timedOut) {

    public boolean successful() {
        return !timedOut && exitCode == 0;
    }
}
