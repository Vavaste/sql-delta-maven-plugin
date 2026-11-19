package io.github.vavaste.sqldelta;

import java.util.ArrayList;
import java.util.List;

import org.apache.maven.plugin.logging.Log;

/**
 * A Maven Log that keeps what it is given as "LEVEL text" lines, and drops what the chosen verbosity hides, as Maven
 * does: QUIET is mvn -q, NORMAL is mvn, DEBUG is mvn -X.
 */
final class RecordingLog implements Log {

    enum Verbosity { DEBUG, NORMAL, QUIET }

    final List<String> lines = new ArrayList<>();
    private final Verbosity verbosity;

    RecordingLog(Verbosity verbosity) {
        this.verbosity = verbosity;
    }

    @Override public boolean isDebugEnabled() { return verbosity == Verbosity.DEBUG; }
    @Override public boolean isInfoEnabled() { return verbosity != Verbosity.QUIET; }
    @Override public boolean isWarnEnabled() { return verbosity != Verbosity.QUIET; }
    @Override public boolean isErrorEnabled() { return true; }

    @Override public void debug(CharSequence text) { add(isDebugEnabled(), "DEBUG", text, null); }
    @Override public void debug(CharSequence text, Throwable error) { add(isDebugEnabled(), "DEBUG", text, error); }
    @Override public void debug(Throwable error) { add(isDebugEnabled(), "DEBUG", "", error); }
    @Override public void info(CharSequence text) { add(isInfoEnabled(), "INFO", text, null); }
    @Override public void info(CharSequence text, Throwable error) { add(isInfoEnabled(), "INFO", text, error); }
    @Override public void info(Throwable error) { add(isInfoEnabled(), "INFO", "", error); }
    @Override public void warn(CharSequence text) { add(isWarnEnabled(), "WARN", text, null); }
    @Override public void warn(CharSequence text, Throwable error) { add(isWarnEnabled(), "WARN", text, error); }
    @Override public void warn(Throwable error) { add(isWarnEnabled(), "WARN", "", error); }
    @Override public void error(CharSequence text) { add(true, "ERROR", text, null); }
    @Override public void error(CharSequence text, Throwable error) { add(true, "ERROR", text, error); }
    @Override public void error(Throwable error) { add(true, "ERROR", "", error); }

    private void add(boolean enabled, String level, CharSequence text, Throwable error) {
        if (enabled) {
            lines.add(level + " " + text + (error == null ? "" : " !" + error.getClass().getSimpleName()));
        }
    }
}
