package peergos.server.util;


import java.io.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

public class  Logging {
    private static final Logger LOG = Logger.getGlobal();
    private static boolean isInitialised = false;
    public static Logger LOG() {
        return LOG;
    }

    /**
     * Initialise logging to a file in PEERGOS_DIR
     * @param a
     */
    public static synchronized void init(Args a) {
        Path logPath = a.fromPeergosDir("logName", "peergos.%g.log");
        int logLimit = a.getInt("logLimit", 1024 * 1024);
        int logCount = a.getInt("logCount", 10);
        boolean logAppend = a.getBoolean("logAppend", true);
        boolean logToConsole = a.getBoolean("logToConsole", false);
        boolean logToFile = a.getBoolean("logToFile", true);

        init(logPath, logLimit, logCount, logAppend, logToConsole, logToFile);
    }

    public static synchronized void init(Path logPath, int logLimit, int logCount, boolean logAppend, boolean logToConsole,
                                         boolean logToFile) {

        if (isInitialised)
            return;

        try {
            if (! logToFile)
                return;

            String logPathS = logPath.toString();
            FileHandler fileHandler = new FileHandler(logPathS, logLimit, logCount, logAppend);

            // tell  console where we're logging to
            LOG().info("Logging to "+ logPathS.replace("%g", "0"));
            LOG().addHandler(fileHandler);
            // also logging to stdout?
            if (! logToConsole)
                LOG().setUseParentHandlers(false);

        } catch (IOException ioe) {
            throw new IllegalStateException(ioe.getMessage(), ioe);
        } finally {
            isInitialised = true;
        }
    }

    /**
     * stream an InputStream to LOG.
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
                    s  = prefix + s;
                LOG().info(s);
            }
        } catch (EOFException eofe) {

        } catch (IOException ioe) {
            if ("Stream closed".equals(ioe.getMessage()))
                LOG().info("Logging from stream  '" + prefix +"' closed");
            else
                LOG().log(Level.WARNING, "Failed to read log message from stream", ioe);
        }
    }
}
