package peergos.server.util;


import java.io.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.*;

public class  Logging {
    private static final Logger LOG = Logger.getGlobal();
    private  static final String NULL_FORMAT =  "NULL_FORMAT";
    private static final Logger NULL_LOG = Logger.getLogger(NULL_FORMAT);

    private static boolean isInitialised = false;
    public static Logger LOG() {
        return LOG;
    }
    private static Logger nullLog() {
        return NULL_LOG;
    }

    /**
     * Initialise logging to a file in PEERGOS_PATH
     * @param a
     */
    public static synchronized void init(Args a) {
        Path logPath = a.fromPeergosDir("log-name", "peergos.%g.log");
        logPath.toFile().getParentFile().mkdirs();
        int logLimit = a.getInt("log-limit", 1024 * 1024);
        int logCount = a.getInt("log-count", 10);
        boolean logAppend = a.getBoolean("log-append", true);
        boolean logToConsole = a.getBoolean("log-to-console", false);
        boolean logToFile = a.getBoolean("log-to-file", true);
        boolean printLogLocation = a.getBoolean("print-log-location", true);

        NULL_LOG.setParent(LOG());

        init(logPath, logLimit, logCount, logAppend, logToConsole, logToFile, printLogLocation);
    }

    public static synchronized void init(Path logPath,
                                         int logLimit,
                                         int logCount,
                                         boolean logAppend,
                                         boolean logToConsole,
                                         boolean logToFile,
                                         boolean printLocation) {

        if (isInitialised)
            return;

        try {
            // also logging to stdout?
            if (! logToConsole)
                LOG().setUseParentHandlers(false);
            if (! logToFile)
                return;

            String logPathS = logPath.toString();
            FileHandler fileHandler = new FileHandler(logPathS, logLimit, logCount, logAppend);
            fileHandler.setFormatter(new WithNullFormatter());

            // tell console where we're logging to
            if (printLocation && logToFile)
                LOG().info("Logging to "+ logPathS.replace("%g", "0"));
            nullLog().setParent(LOG());

            Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
                long id = thread.getId();
                String name = thread.getName();
                String msg = "Uncaught Exception in thread " + id + ":" + name;
                LOG().log(Level.SEVERE, msg, throwable);
            });

            LOG().addHandler(fileHandler);
        } catch (IOException ioe) {
            throw new IllegalStateException(ioe.getMessage(), ioe);
        } finally {
            isInitialised = true;
        }
    }

    private static final Formatter SIMPLE_FORMATTER = new SimpleFormatter();
    private static class WithNullFormatter  extends Formatter {

        /**
         * If the logger-name is NULL_FORMAT just post the message, otherwise use SimpleFormatter.format.
         * @param logRecord
         * @return
         */
        @Override
        public String format(LogRecord logRecord) {
            boolean noFormatting = NULL_FORMAT.equals(logRecord.getLoggerName());

            if (noFormatting)
                return logRecord.getMessage() + "\n";
            return SIMPLE_FORMATTER.format(logRecord);
        }
    }

    /**
     * Stream an InputStream to LOG without any formatting.
     *
     * This is useful when logging from another processes stdout/stderr.
     *
     * @param in Inputstream to be logged
     *
     */
    public static void log(InputStream in, String prefix) {
        BufferedReader bin = new BufferedReader(new InputStreamReader(in));
        try {
            while(true) {
                String s = bin.readLine();
                // reached end of stream?
                if (s ==  null)
                    return;
                if (prefix != null)
                    s = prefix + s;

                nullLog().info(s);
            }
        } catch (EOFException eofe) {

        } catch (IOException ioe) {
            if (! "Stream closed".equals(ioe.getMessage()))
                LOG().log(Level.WARNING, "Failed to read log message from stream", ioe);
        }
    }
}
