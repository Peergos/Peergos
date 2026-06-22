package peergos.server.webdav;

import org.eclipse.jetty.security.ConstraintMapping;
import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.security.HashLoginService;
import org.eclipse.jetty.security.UserStore;
import org.eclipse.jetty.security.authentication.BasicAuthenticator;
import org.eclipse.jetty.security.authentication.DigestAuthenticator;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.security.Constraint;
import org.eclipse.jetty.util.security.Password;
import peergos.server.Main;
import peergos.server.net.MountConfigHandler;
import peergos.server.util.JvmThumbnailer;
import peergos.server.util.Logging;
import peergos.server.webdav.modeshape.webdav.WebdavServlet;
import peergos.server.util.Args;
import peergos.shared.Crypto;
import peergos.shared.crypto.asymmetric.PublicSigningKey;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import peergos.shared.io.ipfs.api.JSONParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

public class WebdavServer {

    private static final String VERSION= "0.1";
    private static final Logger logger = Logging.LOG();

    /** Overload used by MountConfigHandler when it holds its own WebdavFileSystem reference. */
    public static Server startNonBlocking(int port,
                                          String webdavUser, String webdavPassword,
                                          WebdavFileSystem fs,
                                          String authScheme) {
        return startWithServlet(port, webdavUser, webdavPassword, authScheme,
                new WebdavServlet(fs), fs);
    }

    public static Server startNonBlocking(int port,
                                          String webdavUser, String webdavPassword,
                                          String peergosUser, String peergosPassword,
                                          String peergosUrl, String authScheme,
                                          MountConfig config) {
        return startWithServlet(port, webdavUser, webdavPassword, authScheme,
                new WebdavServlet(peergosUser, peergosPassword, peergosUrl, config), null);
    }

    private static Server startWithServlet(int port,
                                           String webdavUser, String webdavPassword,
                                           String authScheme,
                                           WebdavServlet servlet,
                                           WebdavFileSystem snapshotFs) {
        logger.info("Starting WEBDAV server version: " + VERSION + " on port: " + port);
        Crypto crypto = Main.initCrypto();
        PublicSigningKey.addProvider(PublicSigningKey.Type.Ed25519, crypto.signer);
        JvmThumbnailer.initJava();
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setHost("127.0.0.1");
        connector.setPort(port);
        server.setConnectors(new Connector[] {connector});

        //info from:
        //https://stackoverflow.com/questions/44263651/hashloginservice-and-jetty9
        //https://git.eclipse.org/c/jetty/org.eclipse.jetty.project.git/tree/examples/embedded/src/main/java/org/eclipse/jetty/embedded/SecuredHelloHandler.java
        HashLoginService loginService = new HashLoginService("MyRealm");
        UserStore userStore = new UserStore();
        userStore.addUser(webdavUser, new Password(webdavPassword), new String[] { "user"});
        loginService.setUserStore(userStore);
        server.addBean(loginService);

        ConstraintSecurityHandler security = new ConstraintSecurityHandler();
        server.setHandler(security);

        Constraint constraint = new Constraint();
        constraint.setName("auth");
        constraint.setAuthenticate(true);
        constraint.setRoles(new String[] { "user"});

        ConstraintMapping mapping = new ConstraintMapping();
        mapping.setPathSpec("/*");
        mapping.setConstraint(constraint);

        // Allow unauthenticated OPTIONS so Windows WebClient can discover DAV capabilities
        Constraint optionsConstraint = new Constraint();
        optionsConstraint.setName("options");
        optionsConstraint.setAuthenticate(false);

        ConstraintMapping optionsMapping = new ConstraintMapping();
        optionsMapping.setMethod("OPTIONS");
        optionsMapping.setPathSpec("/*");
        optionsMapping.setConstraint(optionsConstraint);

        security.setConstraintMappings(List.of(optionsMapping, mapping));
        if (authScheme == null || authScheme.toLowerCase().equals("digest")) {
            logger.info("Using DIGEST authorization");
            security.setAuthenticator(new DigestAuthenticator());
        } else if (authScheme.toLowerCase().equals("basic")) {
            logger.info("Using BASIC authorization");
            security.setAuthenticator(new BasicAuthenticator());
        } else {
            throw new RuntimeException("Unknown authorization scheme:" + authScheme);
        }
        security.setLoginService(loginService);

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");
        security.setHandler(context);

        if (snapshotFs != null) {
            context.addServlet(new ServletHolder("snapshot", new SnapshotServlet(snapshotFs)),
                    "/peergos/v0/mount/snapshot");
        }

        ServletHolder holderDef = new ServletHolder("default", servlet);
        holderDef.setInitParameter("rootpath","");
        context.addServlet(holderDef,"/*");

        try {
            server.start();
            System.out.println("Webdav bridge started and ready to use at localhost:" + port);
            return server;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static final class SnapshotServlet extends HttpServlet {
        private final WebdavFileSystem fs;
        SnapshotServlet(WebdavFileSystem fs) { this.fs = fs; }

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            Map<String, Object> snapshot = new LinkedHashMap<>(fs.getWriterSnapshot());
            byte[] json = JSONParser.toString(snapshot).getBytes(StandardCharsets.UTF_8);
            resp.setContentType("application/json");
            resp.setContentLength(json.length);
            resp.getOutputStream().write(json);
        }
    }

    public static Server start(Args args) {
        int port = args.getInt("webdav.port", 8090);
        String webdavUser = args.getArg("webdav.username");
        String webdavPWD = args.getArg("PEERGOS_WEBDAV_PASSWORD");
        String username = args.getArg("username");
        String password = args.getArg("PEERGOS_PASSWORD");
        String authScheme = args.getOptionalArg("webdav.authorization.scheme").orElse("digest");
        String peergosUrl = args.getArg("peergos-url");
        MountConfig config = MountConfigHandler.readConfig(args.getPeergosDir());
        Server server = startNonBlocking(port, webdavUser, webdavPWD, username, password, peergosUrl, authScheme, config);
        try {
            server.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return server;
    }
}
