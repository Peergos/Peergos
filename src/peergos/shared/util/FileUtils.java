package peergos.shared.util;

import peergos.shared.cbor.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;

import java.nio.file.*;
import java.util.concurrent.*;
import java.util.function.*;

public class FileUtils {

    /** Get and parse an object from a file, or create and save an object if file doesn't exist
     *
     * @param context
     * @param file
     * @param generator
     * @param parser
     * @param <T>
     * @return
     */
    public static <T extends Cborable> CompletableFuture<T> getOrCreateObject(UserContext context,
                                                                              Path file,
                                                                              Supplier<T> generator,
                                                                              Function<T, CompletableFuture<Boolean>> initializer,
                                                                              Function<byte[], T> parser) {
        return context.getByPath(file).thenCompose(opt -> {
            if (opt.isPresent())
                return opt.get().getInputStream(context.network, context.crypto, x -> {})
                        .thenCompose(in -> Serialize.readFully(in, opt.get().getSize()))
                        .thenApply(parser);
            T val = generator.get();
            byte[] raw = val.serialize();
            String filename = file.getFileName().toString();
            AsyncReader reader = AsyncReader.build(raw);
            return initializer.apply(val).thenCompose(x -> context.getByPath(file.getParent()))
                    .thenCompose(dopt -> dopt.get()
                            .uploadAndReturnFile(filename, reader, raw.length, false, dopt.get().mirrorBatId(), context.network, context.crypto))
                    .thenApply(x -> val);
        });
    }
}
