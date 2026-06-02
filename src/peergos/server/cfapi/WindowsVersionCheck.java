package peergos.server.cfapi;

import peergos.server.util.Logging;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class WindowsVersionCheck {
    private static final Logger LOG = Logging.LOG();
    private static volatile Boolean available = null;

    /**
     * Returns true only when cldapi.dll (the actual CF API DLL — note: header is cfapi.h
     * but the binary is cldapi.dll) is present and the build is >= 16299.
     * cldapi.dll ships with Windows 10/11 desktop but is absent on Windows Server SKUs.
     */
    public static boolean isCfApiAvailable() {
        if (available != null) return available;
        synchronized (WindowsVersionCheck.class) {
            if (available != null) return available;
            if (!System.getProperty("os.name", "").toLowerCase().startsWith("windows")) {
                available = false;
                return false;
            }
            String systemRoot = System.getenv("SystemRoot");
            if (systemRoot == null) systemRoot = "C:\\Windows";
            Path dllPath = Path.of(systemRoot, "System32", "cldapi.dll");
            if (!Files.exists(dllPath)) {
                LOG.info("cldapi.dll not found at " + dllPath + " — CF API unavailable (Windows Server?)");
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
