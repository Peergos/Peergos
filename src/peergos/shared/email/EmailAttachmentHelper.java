package peergos.shared.email;

import jsinterop.annotations.JsMethod;
import peergos.shared.display.FileRef;
import peergos.shared.user.UserContext;
import peergos.shared.user.fs.AsyncReader;
import peergos.shared.user.fs.FileWrapper;
import peergos.shared.util.Pair;
import peergos.shared.util.ProgressConsumer;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Attachments are stored in: attachments/$year/$month/
 */
public class EmailAttachmentHelper {
    private static final Path emailDataDir = Paths.get(".apps", "email", "data");

    @JsMethod
    public static CompletableFuture<String> upload(UserContext context, String username,
                                                                  String directoryPrefix, String pendingDirectory,
                                                                  AsyncReader reader, String fileExtension, int length,
                                                                ProgressConsumer<Long> monitor) {
        String uuid = UUID.randomUUID().toString() + "." + fileExtension;
        return getDirToStoreAttachment(context, username, directoryPrefix, pendingDirectory)
                .thenCompose(dir -> dir.get().uploadAndReturnFile(uuid, reader, length, false, monitor,
                    context.network, context.crypto)
                        .thenApply(hash -> uuid));
    }

    @JsMethod
    public static CompletableFuture<Optional<FileWrapper>> retrieveAttachment(UserContext context, String username,
                                                           String directoryPrefix, String uuid) {
        Path path = Paths.get(username + "/" + emailDataDir + "/" + directoryPrefix + "/attachments/" + uuid);
        return context.getByPath(path);
    }

    private static CompletableFuture<Optional<FileWrapper>> getDirToStoreAttachment(UserContext context,
                                      String username, String directoryPrefix, String pendingDirectory) {
        Path baseDir = Paths.get(username + "/" + emailDataDir + "/" + directoryPrefix + "/" +
                pendingDirectory + "/attachments");
        return context.getByPath(baseDir);
    }
}
