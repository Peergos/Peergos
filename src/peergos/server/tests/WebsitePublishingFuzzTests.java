package peergos.server.tests;

import org.junit.*;
import static org.junit.Assert.*;

import peergos.server.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.storage.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.net.*;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.*;

/** Random sequences of the website publishing operations, against a real server.
 *
 *  Each step points the webroot at one of a set of directories, clears it, publishes it, or
 *  unpublishes it. The directories deliberately contain ancestors and descendants of each other, so
 *  a new webroot is often a sub path of a previous one, and one of them does not exist at all.
 *
 *  After every step the whole world is re-checked rather than just the thing that changed: the
 *  profile value, which directories are publicly readable, and - when the site should be live - a
 *  replay of exactly what GatewayHandler does to serve a request. Mismatches are collected with the
 *  history that produced them rather than failing on the first one, so one run reports every
 *  distinct way the state can go wrong.
 */
public class WebsitePublishingFuzzTests {

    private static final int OPS = Integer.parseInt(System.getProperty("fuzz.ops", "40"));
    private static final long SEED = Long.parseLong(System.getProperty("fuzz.seed", "1"));

    private static final Args args = UserTests.buildArgs().with("useIPFS", "false");
    private static final Crypto crypto = Main.initCrypto();
    private static NetworkAccess network;

    /** Directories that can be a webroot. "gone" is never created. */
    private static final List<String> SITES =
            Arrays.asList("site", "site/inner", "site/inner/deep", "other", "other/pages", "gone");

    @BeforeClass
    public static void init() throws Exception {
        UserService service = Main.PKI_INIT.main(args).localApi;
        ServerMessager.HTTP messager = new ServerMessager.HTTP(
                new JavaPoster(new URI("http://localhost:" + args.getArg("port")).toURL(), false));
        network = NetworkAccess.buildBuffered(service.storage, service.bats, service.coreNode, service.account,
                        service.mutable, 5_000, service.social, service.controller, service.usage, messager,
                        crypto.hasher, Arrays.asList("peergos"), false)
                .withStorage(s -> new UnauthedCachingStorage(s, new RamUserTests.NoopCache(), crypto.hasher));
    }

    @Test
    public void fuzzWebsitePublishing() {
        Random random = new Random(SEED);
        String username = "web" + Math.abs(random.nextInt() % 1_000_000);
        UserContext context = PeergosNetworkUtils.ensureSignedUp(username, "test01", network, crypto);
        for (String site : SITES)
            if (! site.equals("gone"))
                makeSite(context, username, site);

        // what we believe the server should hold
        String webroot = null;
        Set<String> publishedRoots = new LinkedHashSet<>();

        List<String> history = new ArrayList<>();
        List<String> failures = new ArrayList<>();

        for (int op = 0; op < OPS; op++) {
            int choice = random.nextInt(4);
            String site = SITES.get(random.nextInt(SITES.size()));
            String describe = choice == 0 ? "set webroot /" + username + "/" + site
                    : choice == 1 ? "clear webroot"
                    : choice == 2 ? "publish (webroot " + webroot + ")"
                    : "unpublish (webroot " + webroot + ")";
            history.add("[" + op + "] " + describe);
            try {
                switch (choice) {
                    case 0:
                        webroot = "/" + username + "/" + site;
                        ProfilePaths.setWebRoot(context, webroot).join();
                        break;
                    case 1:
                        webroot = "";
                        ProfilePaths.setWebRoot(context, webroot).join();
                        break;
                    case 2:
                        if (ProfilePaths.publishWebroot(context).join())
                            publishedRoots.add(webroot);
                        break;
                    default:
                        if (ProfilePaths.unpublishWebRoot(context).join())
                            publishedRoots.remove(webroot);
                        break;
                }
            } catch (Exception e) {
                record(failures, history, "threw " + messageOf(e));
                // the operation half happened, so re-derive what is public rather than
                // letting one throw cascade into a page of bogus findings
                publishedRoots.clear();
                publishedRoots.addAll(observePublished(context, username));
            }
            check(context, username, webroot, publishedRoots, history, failures);
        }

        if (! failures.isEmpty()) {
            System.err.println("=== website publishing fuzz, seed " + SEED + ", " + failures.size() + " findings ===");
            failures.forEach(System.err::println);
            System.err.println("--- history ---");
            history.forEach(System.err::println);
            fail(failures.size() + " findings, first: " + failures.get(0));
        }
    }

    private static void check(UserContext context,
                              String username,
                              String webroot,
                              Set<String> publishedRoots,
                              List<String> history,
                              List<String> failures) {
        String stored = ProfilePaths.getWebRoot(username, context).join().orElse(null);
        if (! Objects.equals(webroot, stored))
            record(failures, history, "profile webroot is " + stored + ", expected " + webroot);

        // publishing a directory exposes everything under it, so a site is readable if it is at or
        // below anything still published
        for (String site : SITES) {
            String path = "/" + username + "/" + site;
            boolean expected = publishedRoots.stream()
                    .anyMatch(root -> path.equals(root) || path.startsWith(root + "/"));
            boolean actual = context.getPublicFile(Path.of(path)).join().isPresent();
            if (expected != actual)
                record(failures, history, path + " is " + (actual ? "public" : "not public")
                        + ", expected " + (expected ? "public" : "not public")
                        + " (published: " + publishedRoots + ")");
        }

        // whenever the site should be live, serve it the way the gateway does
        if (webroot != null && publishedRoots.contains(webroot)) {
            try {
                String served = serveLikeGateway(username);
                if (! webroot.equals(served))
                    record(failures, history, "gateway served " + served + ", expected " + webroot);
            } catch (Exception e) {
                record(failures, history, "gateway could not serve " + webroot + ": " + messageOf(e));
            }
        }
    }

    /** The exact sequence GatewayHandler performs, returning the marker in the served index.html. */
    private static String serveLikeGateway(String owner) {
        Path profileEntry = Path.of("/" + owner + "/.profile/webroot");
        FileWrapper field = UserContext.getPublicFile(profileEntry, network, crypto).join()
                .orElseThrow(() -> new IllegalStateException("no published webroot entry"));
        Path toWebRoot = Path.of(new String(Serialize.readFully(field, crypto, network).join()));
        FileWrapper webRoot = UserContext.getPublicFile(toWebRoot, network, crypto).join()
                .orElseThrow(() -> new IllegalStateException("web root not present"));
        FileWrapper index = webRoot.getChild("index.html", crypto.hasher, network).join()
                .orElseThrow(() -> new IllegalStateException("no index.html in web root"));
        byte[] data = Serialize.readFully(index, crypto, network).join();
        return new String(data);
    }

    /** A directory at username/relative holding an index.html naming its own path. */
    private static void makeSite(UserContext context, String username, String relative) {
        String path = "/" + username;
        for (String name : relative.split("/")) {
            FileWrapper parent = context.getByPath(path).join().get();
            if (parent.getChild(name, crypto.hasher, network).join().isEmpty())
                parent.mkdir(name, network, false, parent.mirrorBatId(), crypto).join();
            path = path + "/" + name;
        }
        byte[] marker = path.getBytes();
        FileWrapper dir = context.getByPath(path).join().get();
        dir.uploadFileSection("index.html", new AsyncReader.ArrayBacked(marker), false, 0, marker.length,
                Optional.empty(), true, network, crypto, () -> false, l -> {},
                crypto.random.randomBytes(32), Optional.empty(),
                Optional.of(peergos.shared.storage.auth.Bat.random(crypto.random)), dir.mirrorBatId()).join();
    }

    /** The published set as the server actually has it: public paths with no public ancestor. */
    private static Set<String> observePublished(UserContext context, String username) {
        Set<String> publicPaths = SITES.stream()
                .map(site -> "/" + username + "/" + site)
                .filter(path -> context.getPublicFile(Path.of(path)).join().isPresent())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return publicPaths.stream()
                .filter(path -> publicPaths.stream().noneMatch(other -> path.startsWith(other + "/")))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static void record(List<String> failures, List<String> history, String finding) {
        String at = history.get(history.size() - 1);
        if (failures.stream().anyMatch(f -> f.endsWith(finding)))
            return; // one report per distinct finding, at the step that first produced it
        failures.add(at + ": " + finding);
    }

    private static String messageOf(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null)
            cause = cause.getCause();
        return cause.getClass().getSimpleName() + " " + cause.getMessage();
    }
}
