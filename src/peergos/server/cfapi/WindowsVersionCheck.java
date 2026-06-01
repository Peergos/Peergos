package peergos.server.cfapi;

import peergos.server.util.Logging;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class WindowsVersionCheck {
    private static final Logger LOG = Logging.LOG();
    private static volatile Boolean available = null;

    /** CF API requires Windows 10 build 16299 (v1709, October 2017). */
    public static boolean isCfApiAvailable() {
        if (available != null) return available;
        synchronized (WindowsVersionCheck.class) {
            if (available != null) return available;
            if (!System.getProperty("os.name", "").toLowerCase().startsWith("windows")) {
                available = false;
                return false;
            }
            try {
                available = getWindowsBuildNumber() >= 16299;
            } catch (Exception e) {
                LOG.warning("Could not determine Windows build number: " + e.getMessage());
                available = false;
            }
            return available;
        }
    }

    private static int getWindowsBuildNumber() throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                "reg.exe", "query",
                "HKLM\\SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion",
                "/v", "CurrentBuildNumber");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            output = reader.lines().collect(Collectors.joining("\n"));
        }
        p.waitFor();
        for (String line : output.split("\n")) {
            line = line.trim();
            if (line.contains("CurrentBuildNumber")) {
                String[] parts = line.split("\\s+");
                return Integer.parseInt(parts[parts.length - 1].trim());
            }
        }
        throw new Exception("Could not parse build number from: " + output);
    }
}
