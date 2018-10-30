package peergos.server.util;


import java.io.IOException;
import java.nio.file.Paths;
import java.util.logging.FileHandler;
import java.util.logging.Logger;

public class Logging {
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

        if (isInitialised)
            return;

        String logName = a.getArg("logName", "peergos.%g.log");
        int logLimit = a.getInt("logLimit", 1024 * 1024);
        int logCount = a.getInt("logCount", 10);
        boolean logAppend = a.getBoolean("logAppend", true);
        boolean logToConsole = a.getBoolean("logToConsole", false);
        boolean logToFile = a.getBoolean("logToFile", true);
        String peergosDir = a.getArg(Args.PEERGOS_DIR, System.getProperty("user.dir"));
        String logPath = Paths.get(peergosDir, logName).toString();
        try {
            if (! logToFile)
                return;

            FileHandler fileHandler = new FileHandler(logPath, logLimit, logCount, logAppend);

            // tell  console where we're logging to
            LOG().info("Logging to file"+ logPath.replace("%g", "0"));

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
}
