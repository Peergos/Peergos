package peergos.server;

import peergos.server.util.Args;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;

public class DesktopApp {

    public static void launch(Args args, int port, URI api) throws IOException {
        boolean flatpak = args.hasArg("flatpak");
        boolean isLinux = "linux".equalsIgnoreCase(System.getProperty("os.name"));
        boolean isWindows = System.getProperty("os.name").toLowerCase().startsWith("windows");
        boolean isMacOS = System.getProperty("os.name").toLowerCase().startsWith("mac");

        try {
            if (flatpak) {
                ProcessBuilder pb = new ProcessBuilder(
                        "flatpak.sh",
                        Integer.toString(port)
                );
                pb.inheritIO();
                Process p = pb.start();
                p.onExit().thenAccept(done -> {
                    System.exit(0);
                });
            } else if (isWindows) {
                String edgePath = "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe";
                if (!new File(edgePath).exists()) {
                    edgePath = "C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe";
                }

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
                    System.out.println("Webview closed, shutting down...");
                    System.exit(0);
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
}
