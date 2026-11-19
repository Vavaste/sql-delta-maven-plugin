package io.github.vavaste.sqldelta;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.apache.maven.plugin.MojoExecutionException;

/**
 * Runs the engine in a class loader of its own: the plugin jar, Liquibase and the project's class path, with the JDK
 * platform loader as parent. The engine then sees the project's Hibernate, Spring and JDBC driver and nothing of
 * Maven's. It is called by reflection with JDK types only, so the plugin realm never links against Liquibase.
 */
final class EngineLauncher {

    private static final String JBOSS_LOGGING_PROVIDER = "org.jboss.logging.provider";

    private static final Object LOCK = new Object();

    static Map<String, Object> run(List<File> classpath, Map<String, Object> request, MavenLog loggers)
            throws MojoExecutionException {
        synchronized (LOCK) {
            EngineClassLoader loader = new EngineClassLoader(urls(classpath));

            boolean jarCaches = URLConnection.getDefaultUseCaches("jar");
            URLConnection.setDefaultUseCaches("jar", false);
            // Without it jboss-logging picks Logback or Log4j from the class path and bypasses MavenLog.
            boolean setProvider = System.getProperties().putIfAbsent(JBOSS_LOGGING_PROVIDER, "slf4j") == null;
            try {
                return runInEngineThread(loader, request, loggers);
            } finally {
                try {
                    loader.close();
                } catch (IOException e) {
                    // ignore: the engine has run and the plugin is about to exit
                }
                if (setProvider) {
                    System.clearProperty(JBOSS_LOGGING_PROVIDER);
                }
                URLConnection.setDefaultUseCaches("jar", jarCaches);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> runInEngineThread(ClassLoader loader, Map<String, Object> request,
            MavenLog loggers) throws MojoExecutionException {
        AtomicReference<Object> response = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                Class<?> engine = Class.forName(EngineProtocol.ENGINE_CLASS, true, loader);
                response.set(engine.getMethod("run", Map.class, Function.class).invoke(null, request, loggers));
            } catch (InvocationTargetException e) {
                failure.set(e.getCause());
            } catch (Throwable e) {
                failure.set(e);
            }
        }, "sql-delta-engine");
        thread.setDaemon(true);
        thread.setContextClassLoader(loader);
        thread.start();
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MojoExecutionException("Interrupted while the sql-delta engine was running");
        }
        if (failure.get() != null) {
            throw new MojoExecutionException("The sql-delta engine failed: "
                    + loggers.redact(failure.get().toString()));
        }
        return (Map<String, Object>) response.get();
    }

    private static URL[] urls(List<File> classpath) throws MojoExecutionException {
        URL[] urls = new URL[classpath.size()];
        for (int i = 0; i < urls.length; i++) {
            try {
                urls[i] = classpath.get(i).toURI().toURL();
            } catch (MalformedURLException e) {
                throw new MojoExecutionException("Cannot put " + classpath.get(i) + " on the engine class path", e);
            }
        }
        return urls;
    }


    static final class EngineClassLoader extends URLClassLoader {

        private static final String SERVICES = "META-INF/services/";
        private static final String ENGINE_SERVICES = "META-INF/engine-services/";
        private static final Set<String> LOGGING_CONFIGURATIONS = Set.of("logback.xml", "logback-test.xml",
                "log4j.properties", "log4j.xml", "commons-logging.properties", "simplelogger.properties");

        EngineClassLoader(URL[] urls) {
            super("sql-delta-engine", urls, ClassLoader.getPlatformClassLoader());
        }

        @Override
        public URL findResource(String name) {
            if (isLoggingConfiguration(name)) {
                return null;
            }
            URL engineService = engineService(name);
            return engineService != null ? engineService : super.findResource(name);
        }

        @Override
        public Enumeration<URL> findResources(String name) throws IOException {
            if (isLoggingConfiguration(name)) {
                return Collections.emptyEnumeration();
            }
            URL engineService = engineService(name);
            return engineService != null ? Collections.enumeration(List.of(engineService)) : super.findResources(name);
        }

        private static boolean isLoggingConfiguration(String name) {
            return LOGGING_CONFIGURATIONS.contains(name) || name.startsWith("log4j2");
        }

        private URL engineService(String name) {
            return name.startsWith(SERVICES)
                    ? super.findResource(ENGINE_SERVICES + name.substring(SERVICES.length()))
                    : null;
        }
    }
}
