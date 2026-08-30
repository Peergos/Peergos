package peergos.server;

import peergos.server.util.Args;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;

public class DesktopApp {

    /** Set when we were started by the user's login item, and so should come up as a
     *  tray icon with no window. The GUI host is a separate process on every platform,
     *  so this has to be handed on to it; each host ignores it if it has no tray, since
     *  a hidden window with no tray is an app the user cannot reach or quit. */
    private static final String MINIMISED_ENV = "PEERGOS_MINIMISED";

    /** Our own pid, so a window host can tell when the server it belongs to has gone. */
    private static final String SERVER_PID_ENV = "PEERGOS_SERVER_PID";

    public static void launch(Args args, int port, URI api) throws IOException {
        boolean flatpak = args.hasArg("flatpak");
        boolean minimised = args.getBoolean("minimised", false);
        boolean isLinux = "linux".equalsIgnoreCase(System.getProperty("os.name"));
        boolean isWindows = System.getProperty("os.name").toLowerCase().startsWith("windows");
        boolean isMacOS = System.getProperty("os.name").toLowerCase().startsWith("mac");

        try {
            if (flatpak) {
                // The window hides to the tray when closed, so this only exits
                // when the user picks "Close Peergos" (or has no tray at all).
                ProcessBuilder pb = new ProcessBuilder(
                        "peergos-window",
                        Integer.toString(port)
                );
                if (minimised)
                    pb.environment().put(MINIMISED_ENV, "1");
                pb.inheritIO();
                Process p = pb.start();
                p.onExit().thenAccept(done -> {
                    System.exit(0);
                });
            } else if (isWindows) {
                Path jar = Path.of(Main.class.getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .toURI());
                Path webviewExe = jar.getParent().resolve("PeergosWebView.exe");
                if (webviewExe.toFile().exists()) {
                    ProcessBuilder pb = new ProcessBuilder(webviewExe.toString());
                    pb.environment().put("PEERGOS_PORT", "" + port);
                    // Windows does not reparent an orphan, so the host cannot spot a dead
                    // parent by watching its own ppid the way the linux host does. Told the
                    // pid instead, it can hold a handle on us and exit when we do.
                    pb.environment().put(SERVER_PID_ENV, Long.toString(ProcessHandle.current().pid()));
                    if (minimised)
                        pb.environment().put(MINIMISED_ENV, "1");
                    pb.inheritIO();
                    Process webviewProcess = pb.start();
                    webviewProcess.onExit().thenAccept(done -> {
                        if (done.exitValue() == 0) {
                            System.out.println("WebView closed, shutting down...");
                            System.exit(0);
                        } else {
                            // WebView2 runtime not installed — fall back to Edge
                            launchWindowsFallback(port, api);
                        }
                    });
                } else {
                    launchWindowsFallback(port, api);
                }
            } else if (isMacOS) {
                Path jar = Path.of(Main.class.getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .toURI());
                Path binary = jar.getParent().resolve("PeergosWebView");
                ProcessBuilder pb = new ProcessBuilder(
                        binary.toString()
                );
                // pass port via env var
                pb.environment().put("PEERGOS_PORT", "" + port);
                pb.inheritIO();
                Process webviewProcess = pb.start();

                webviewProcess.onExit().thenAccept(done -> {
                    if (done.exitValue() == 0) {
                        System.out.println("Webview closed, shutting down...");
                        System.exit(0);
                    } else {
                        try {
                            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))
                                Desktop.getDesktop().browse(api);
                        } catch (Throwable t) {
                            t.printStackTrace();
                        }
                        System.out.println("Webview failed to start. Please open http://localhost:" + port + " in your browser.");
                    }
                });
            } else if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(api);
            } else {
                if (isLinux) // Fix Snap installer
                    Runtime.getRuntime().exec(new String[] {"xdg-open", "http://localhost:" + port});
                System.out.println("Please open http://localhost:" + port + " in your browser.");
            }
        } catch (Throwable t) {
            if (isLinux)
                Runtime.getRuntime().exec(new String[] {"xdg-open", "http://localhost:" + port});
            if (isWindows || isMacOS)
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))
                    Desktop.getDesktop().browse(api);
            t.printStackTrace();
            System.out.println("Please open http://localhost:" + port + " in your browser.");
        }
    }

    // No WebView2, so no tray either: this shows a window even when minimised was asked
    // for, on the same grounds as the hosts' own degrade gate - visible beats unreachable.
    private static void launchWindowsFallback(int port, URI api) {
        String edgePath = "C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe";
        if (!new File(edgePath).exists())
            edgePath = "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe";
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    edgePath,
                    "--app=http://localhost:" + port,
                    "--disable-extensions",
                    "--user-data-dir=" + System.getenv("APPDATA") + "\\Peergos\\edge-data"
            );
            Process edgeProcess = pb.start();
            edgeProcess.onExit().thenAccept(done -> {
                System.out.println("Edge closed, shutting down...");
                System.exit(0);
            });
        } catch (Throwable t) {
            try {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))
                    Desktop.getDesktop().browse(api);
            } catch (Throwable ignored) {}
            System.out.println("Please open http://localhost:" + port + " in your browser.");
        }
    }
}
