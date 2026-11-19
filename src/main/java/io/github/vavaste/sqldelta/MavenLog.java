package io.github.vavaste.sqldelta;

import java.text.MessageFormat;
import java.util.List;
import java.util.ResourceBundle;
import java.util.function.Function;
import java.util.regex.Pattern;

import org.apache.maven.plugin.logging.Log;

final class MavenLog implements Function<String, System.Logger> {

    private static final String OWN_LOGGERS = "io.github.vavaste.sqldelta";

    // Warnings that the way liquibase-hibernate boots Hibernate always triggers, and the user cannot act on.
    // HHH000181 is the Hibernate 6 code of HHH102003 that stands for no connection to database
    private static final List<String> DEMOTED = List.of("HHH90000021", "HHH90000025", "HHH102003", "HHH000181");

    private final Log log;
    private final String url;
    private final String sanitizedUrl;
    private final Pattern passwordPattern;

    MavenLog(Log log, String url, String password) {
        this.log = log;
        this.url = url;
        this.sanitizedUrl = JdbcUrls.sanitize(url);
        this.passwordPattern = password != null && password.length() > 3
                ? Pattern.compile("(?<![A-Za-z0-9])" + Pattern.quote(password) + "(?![A-Za-z0-9])") : null;
    }

    @Override
    public System.Logger apply(String name) {
        return new Logger(name, name.equals(OWN_LOGGERS) || name.startsWith(OWN_LOGGERS + "."));
    }

    String redact(String text) {
        String result = text.replace(url, sanitizedUrl);
        return passwordPattern == null ? result : passwordPattern.matcher(result).replaceAll("****");
    }

    private final class Logger implements System.Logger {

        private final String name;
        private final boolean own;

        Logger(String name, boolean own) {
            this.name = name;
            this.own = own;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public boolean isLoggable(Level level) {
            // TRACE must stay off, it floods too much
            return switch (level) {
                case ERROR -> log.isErrorEnabled();
                case WARNING -> log.isWarnEnabled();
                case INFO -> own ? log.isInfoEnabled() : log.isDebugEnabled();
                case DEBUG -> log.isDebugEnabled();
                default -> false;
            };
        }

        @Override
        public void log(Level level, ResourceBundle bundle, String message, Throwable thrown) {
            if (!isLoggable(level)) {
                return;
            }
            String text = redact(message == null ? "" : message);
            boolean demoted = !own && DEMOTED.stream().anyMatch(code -> text.stripLeading().startsWith(code));
            if (level == Level.ERROR) {
                log.error(text, thrown);
            } else if (level == Level.WARNING && !demoted) {
                log.warn(text, thrown);
            } else if (level == Level.INFO && own) {
                log.info(text, thrown);
            } else {
                log.debug(own ? text : "[" + name + "] " + text, thrown);
            }
        }

        @Override
        public void log(Level level, ResourceBundle bundle, String format, Object... parameters) {
            if (isLoggable(level)) {
                boolean plain = parameters == null || parameters.length == 0;
                log(level, bundle, plain ? format : MessageFormat.format(format, parameters), (Throwable) null);
            }
        }
    }
}
