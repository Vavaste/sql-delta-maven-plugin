package io.github.vavaste.sqldelta.engine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import liquibase.logging.core.AbstractLogService;
import liquibase.logging.core.AbstractLogger;

/**
 * Sends what the engine and its libraries log to the loggers the Maven side passes to Engine.run
 */
final class EngineLogging {

    private static final Handler FORWARDER = new ForwardingHandler();
    private static volatile Function<String, System.Logger> loggers = System::getLogger;
    private static final Map<Logger, Boolean> redirected = new LinkedHashMap<>();

    private EngineLogging() {
    }

    static synchronized void install(Function<String, System.Logger> runLoggers) {
        loggers = runLoggers;
        for (String name : List.of("liquibase", "org.hibernate", "org.springframework", "org.postgresql",
                "com.zaxxer", "org.jboss")) {
            Logger logger = Logger.getLogger(name);
            redirected.put(logger, logger.getUseParentHandlers());
            logger.addHandler(FORWARDER);
            logger.setUseParentHandlers(false);
        }
    }

    static synchronized void restore() {
        redirected.forEach((logger, useParentHandlers) -> {
            logger.removeHandler(FORWARDER);
            logger.setUseParentHandlers(useParentHandlers);
        });
        redirected.clear();
        loggers = System::getLogger;
    }

    static boolean enabled(String name, System.Logger.Level level) {
        Function<String, System.Logger> current = loggers;
        return current != null && current.apply(name).isLoggable(level);
    }


    static void log(String name, System.Logger.Level level, String message, Throwable thrown) {
        Function<String, System.Logger> current = loggers;
        if (current != null && current.apply(name).isLoggable(level)) {
            String text = message == null ? "" : message;
            current.apply(name).log(level, thrown == null ? text : text + System.lineSeparator() + stackTrace(thrown));
        }
    }

    static String stackTrace(Throwable thrown) {
        StringWriter text = new StringWriter();
        thrown.printStackTrace(new PrintWriter(text));
        return text.toString().stripTrailing();
    }

    private static System.Logger.Level level(Level level) {
        int value = level.intValue();
        return value >= Level.SEVERE.intValue() ? System.Logger.Level.ERROR
                : value >= Level.WARNING.intValue() ? System.Logger.Level.WARNING
                : value >= Level.INFO.intValue() ? System.Logger.Level.INFO
                : value >= Level.FINE.intValue() ? System.Logger.Level.DEBUG : System.Logger.Level.TRACE;
    }

    /** Liquibase's log, installed in the run's liquibase scope so that nothing outside the run sees it. */
    static final class LiquibaseLogService extends AbstractLogService {

        @Override
        public int getPriority() {
            return PRIORITY_SPECIALIZED;
        }

        @Override
        public liquibase.logging.Logger getLog(Class type) {
            String name = type == null ? "liquibase" : type.getName();
            return new AbstractLogger() {
                @Override
                public void log(Level level, String message, Throwable thrown) {
                    EngineLogging.log(name, level(level), message, thrown);
                }
            };
        }
    }

    private static final class ForwardingHandler extends Handler {

        private final SimpleFormatter formatter = new SimpleFormatter();

        @Override
        public void publish(LogRecord record) {
            if (record != null) {
                String name = record.getLoggerName() == null ? "java.util.logging" : record.getLoggerName();
                log(name, level(record.getLevel()), formatter.formatMessage(record), record.getThrown());
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }
}
