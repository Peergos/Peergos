package peergos.server.webdav;

import org.eclipse.jetty.security.ConstraintMapping;
import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.security.HashLoginService;
import org.eclipse.jetty.security.UserStore;
import org.eclipse.jetty.security.authentication.DigestAuthenticator;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.security.Constraint;
import org.eclipse.jetty.util.security.Password;
import peergos.server.util.Logging;
import peergos.server.webdav.modeshape.webdav.WebdavServlet;
import peergos.server.util.Args;

import java.util.Collections;
import java.util.logging.Logger;

class WebDAVServer {

    private static final String VERSION= "0.1";
    private static final Logger logger = Logging.LOG();

    public void start(Args args) throws Exception {
        int port = args.getInt("webdav.port", 8090);
        logger.info( "Starting WEBDAV server version: " + VERSION + " on port: " + port);
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(port);
        server.setConnectors(new Connector[] {connector});

        String webdavUser = args.getArg("webdav.user");
        String webdavPWD = args.getArg("webdav.password");
        String username = args.getArg("username");
        String password = args.getArg("password");

        //info from:
        //https://stackoverflow.com/questions/44263651/hashloginservice-and-jetty9
        //https://git.eclipse.org/c/jetty/org.eclipse.jetty.project.git/tree/examples/embedded/src/main/java/org/eclipse/jetty/embedded/SecuredHelloHandler.java
        HashLoginService loginService = new HashLoginService("MyRealm");
        UserStore userStore = new UserStore();
        userStore.addUser(webdavUser, new Password(webdavPWD), new String[] { "user"});
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

        security.setConstraintMappings(Collections.singletonList(mapping));
        security.setAuthenticator(new DigestAuthenticator());
        security.setLoginService(loginService);

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");
        security.setHandler(context);

        ServletHolder holderDef = new ServletHolder("default", new WebdavServlet(username, password));
        holderDef.setInitParameter("rootpath","");
        context.addServlet(holderDef,"/*");

        server.start();
        server.join();
    }
}
