package peergos.shared.user;

import jsinterop.annotations.JsMethod;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** This class stores locations of the different components of a user's profile
 *
 *  Each component is a separate file and can thus be shared or made public individually.
 */
public class ProfilePaths {

    public static final Path ROOT = Paths.get(".profile");
    private static final Path PHOTO = ROOT.resolve("photo");
    public static final Path PHOTO_HIGH_RES = PHOTO.resolve("highres");
    public static final Path BIO = ROOT.resolve("bio");
    public static final Path STATUS = ROOT.resolve("status");
    public static final Path FIRSTNAME = ROOT.resolve("firstname");
    public static final Path LASTNAME = ROOT.resolve("lastname");
    public static final Path PHONE = ROOT.resolve("phone");
    public static final Path EMAIL = ROOT.resolve("email");
    public static final Path WEBROOT = ROOT.resolve("webroot"); // The path in Peergos to this users web root

    private static <V> CompletableFuture<Optional<V>> getAndParse(Path p, Function<byte[], V> parser, UserContext viewer) {
        return viewer.getByPath(p)
                .thenCompose(opt -> opt.map(f -> Serialize.readFully(f, viewer.crypto, viewer.network)
                        .thenApply(parser)
                        .thenApply(Optional::of))
                        .orElse(Futures.of(Optional.empty())));
    }

    private static <V> CompletableFuture<Boolean> serializeAndSet(Path p, V val, Function<V, byte[]> serialize, UserContext user) {
        byte[] raw = serialize.apply(val);
        return user.getUserRoot()
                .thenCompose(home -> home.getOrMkdirs(p.getParent(), user.network, true, user.crypto))
                .thenCompose(parent -> parent.uploadOrReplaceFile(p.getFileName().toString(),
                        AsyncReader.build(raw), raw.length, user.network, user.crypto, x -> {},
                        user.crypto.random.randomBytes(RelativeCapability.MAP_KEY_LENGTH)))
                .thenApply(x -> true);
    }

    @JsMethod
    public static CompletableFuture<Optional<String>> getProfilePhoto(String user, UserContext viewer) {
        return getAndParse(Paths.get(user).resolve(PHOTO_HIGH_RES), String::new, viewer);
    }

    @JsMethod
    public static CompletableFuture<Boolean> setProfilePhoto(UserContext user, String base64Str) {
        return serializeAndSet(PHOTO_HIGH_RES, base64Str, String::getBytes, user);
    }

    @JsMethod
    public static CompletableFuture<Optional<String>> getBio(String user, UserContext viewer) {
        return getAndParse(Paths.get(user).resolve(BIO), String::new, viewer);
    }

    @JsMethod
    public static CompletableFuture<Boolean> setBio(UserContext user, String bio) {
        return serializeAndSet(BIO, bio, String::getBytes, user);
    }

    @JsMethod
    public static CompletableFuture<Optional<String>> getStatus(String user, UserContext viewer) {
        return getAndParse(Paths.get(user).resolve(STATUS), String::new, viewer);
    }

    @JsMethod
    public static CompletableFuture<Boolean> setStatus(UserContext user, String status) {
        return serializeAndSet(STATUS, status, String::getBytes, user);
    }

    @JsMethod
    public static CompletableFuture<Optional<String>> getFirstName(String user, UserContext viewer) {
        return getAndParse(Paths.get(user).resolve(FIRSTNAME), String::new, viewer);
    }

    @JsMethod
    public static CompletableFuture<Boolean> setFirstName(UserContext user, String firstname) {
        return serializeAndSet(FIRSTNAME, firstname, String::getBytes, user);
    }

    @JsMethod
    public static CompletableFuture<Optional<String>> getLastName(String user, UserContext viewer) {
        return getAndParse(Paths.get(user).resolve(LASTNAME), String::new, viewer);
    }

    @JsMethod
    public static CompletableFuture<Boolean> setLastName(UserContext user, String lastname) {
        return serializeAndSet(LASTNAME, lastname, String::getBytes, user);
    }

    @JsMethod
    public static CompletableFuture<Optional<String>> getPhone(String user, UserContext viewer) {
        return getAndParse(Paths.get(user).resolve(PHONE), String::new, viewer);
    }

    @JsMethod
    public static CompletableFuture<Boolean> setPhone(UserContext user, String phone) {
        return serializeAndSet(PHONE, phone, String::getBytes, user);
    }

    @JsMethod
    public static CompletableFuture<Optional<String>> getEmail(String user, UserContext viewer) {
        return getAndParse(Paths.get(user).resolve(EMAIL), String::new, viewer);
    }

    @JsMethod
    public static CompletableFuture<Boolean> setEmail(UserContext user, String email) {
        return serializeAndSet(EMAIL, email, String::getBytes, user);
    }

    @JsMethod
    public static CompletableFuture<Optional<String>> getWebRoot(String user, UserContext viewer) {
        return getAndParse(Paths.get(user).resolve(WEBROOT), String::new, viewer);
    }

    @JsMethod
    public static CompletableFuture<Boolean> setWebRoot(UserContext user, String webroot) {
        return serializeAndSet(WEBROOT, webroot, String::getBytes, user);
    }

    public static CompletableFuture<Boolean> publishWebroot(UserContext user) {
        // first publish the actual web root, then publish the profile entry linking to the webroot
        return getWebRoot(user.username, user)
                .thenCompose(popt -> {
                    if (popt.isEmpty())
                        return Futures.of(Optional.empty());
                    return user.getByPath(popt.get())
                            .thenCompose(fopt -> fopt.map(user::makePublic)
                                    .map(f -> f.thenApply(Optional::of))
                                    .orElse(Futures.of(Optional.empty())));
                }).thenCompose(res -> {
                    if (res.isEmpty())
                        return Futures.of(Optional.empty());
                    return user.getByPath(Paths.get(user.username).resolve(WEBROOT))
                            .thenCompose(opt -> opt.map(user::makePublic)
                                    .map(f -> f.thenApply(Optional::of))
                                    .orElse(Futures.of(Optional.empty())));
                }).thenApply(x -> true);
    }
}
