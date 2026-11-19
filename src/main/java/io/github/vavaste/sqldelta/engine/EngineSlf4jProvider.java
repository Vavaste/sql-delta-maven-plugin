package io.github.vavaste.sqldelta.engine;

import org.slf4j.ILoggerFactory;
import org.slf4j.IMarkerFactory;
import org.slf4j.Marker;
import org.slf4j.event.Level;
import org.slf4j.helpers.BasicMarkerFactory;
import org.slf4j.helpers.LegacyAbstractLogger;
import org.slf4j.helpers.MessageFormatter;
import org.slf4j.helpers.NOPMDCAdapter;
import org.slf4j.spi.MDCAdapter;
import org.slf4j.spi.SLF4JServiceProvider;

public final class EngineSlf4jProvider implements SLF4JServiceProvider {

    private final ILoggerFactory loggerFactory = ForwardingLogger::new;
    private final IMarkerFactory markerFactory = new BasicMarkerFactory();
    private final MDCAdapter mdcAdapter = new NOPMDCAdapter();

    @Override
    public ILoggerFactory getLoggerFactory() {
        return loggerFactory;
    }

    @Override
    public IMarkerFactory getMarkerFactory() {
        return markerFactory;
    }

    @Override
    public MDCAdapter getMDCAdapter() {
        return mdcAdapter;
    }

    @Override
    public String getRequestedApiVersion() {
        // slf4j 2.0.x accepts any requested version that starts with 2.0
        return "2.0.99";
    }

    @Override
    public void initialize() {
    }

    private static final class ForwardingLogger extends LegacyAbstractLogger {

        ForwardingLogger(String name) {
            this.name = name;
        }

        @Override
        public boolean isTraceEnabled() {
            return EngineLogging.enabled(name, System.Logger.Level.TRACE);
        }

        @Override
        public boolean isDebugEnabled() {
            return EngineLogging.enabled(name, System.Logger.Level.DEBUG);
        }

        @Override
        public boolean isInfoEnabled() {
            return EngineLogging.enabled(name, System.Logger.Level.INFO);
        }

        @Override
        public boolean isWarnEnabled() {
            return EngineLogging.enabled(name, System.Logger.Level.WARNING);
        }

        @Override
        public boolean isErrorEnabled() {
            return EngineLogging.enabled(name, System.Logger.Level.ERROR);
        }

        @Override
        protected String getFullyQualifiedCallerName() {
            return null;
        }

        @Override
        protected void handleNormalizedLoggingCall(Level level, Marker marker, String pattern, Object[] arguments,
                                                   Throwable thrown) {
            System.Logger.Level target = System.Logger.Level.valueOf(level == Level.WARN ? "WARNING" : level.name());
            EngineLogging.log(name, target, MessageFormatter.basicArrayFormat(pattern, arguments), thrown);
        }
    }
}
