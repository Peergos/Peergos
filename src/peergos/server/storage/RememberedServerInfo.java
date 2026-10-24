package peergos.server.storage;

import peergos.shared.cbor.CborObject;
import peergos.shared.cbor.Cborable;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.storage.BlockStoreProperties;
import peergos.shared.storage.ContentAddressedStorage;
import peergos.shared.storage.DelegatingStorage;
import peergos.shared.util.Futures;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/** The server's identities and block store properties, remembered on disk between runs.
 *
 *  Both are asked for before anything else can happen: by this app as it builds its storage,
 *  and by the page as it builds its network. Answered from the file they cost no round trip,
 *  so the page loads without waiting on the server, and loads with the server out of reach,
 *  which is what lets a cached sign-in work offline. The server is asked again in the
 *  background, once per start, and the file follows what it says.
 *
 *  Only these two: a server's identity does not change between runs, and anything that can
 *  is left to the server. In particular id() still goes to the server, and directToOrigin()
 *  is the server's own, so a probe of the origin's id says whether it can be reached. */
public class RememberedServerInfo extends DelegatingStorage {
    public static final String FILENAME = "server-info.cbor";

    private final ContentAddressedStorage server;
    private final Path file;
    private volatile List<Cid> ids;
    private volatile BlockStoreProperties props;
    private final AtomicBoolean refreshed = new AtomicBoolean(false);

    public RememberedServerInfo(ContentAddressedStorage server, Path file) {
        super(server);
        this.server = server;
        this.file = file;
        load();
    }

    @Override
    public CompletableFuture<List<Cid>> ids() {
        List<Cid> known = ids;
        if (known != null) {
            refresh();
            return Futures.of(known);
        }
        return server.ids().thenApply(fresh -> {
            ids = fresh;
            save();
            return fresh;
        });
    }

    @Override
    public CompletableFuture<BlockStoreProperties> blockStoreProperties() {
        BlockStoreProperties known = props;
        if (known != null) {
            refresh();
            return Futures.of(known);
        }
        return server.blockStoreProperties().thenApply(fresh -> {
            props = fresh;
            save();
            return fresh;
        });
    }

    @Override
    public ContentAddressedStorage directToOrigin() {
        return server.directToOrigin();
    }

    /** What the server says now, kept for next time. Once per start, and nothing waits on it. */
    private void refresh() {
        if (! refreshed.compareAndSet(false, true))
            return;
        server.ids()
                .thenCompose(freshIds -> server.blockStoreProperties().thenApply(freshProps -> {
                    ids = freshIds;
                    props = freshProps;
                    save();
                    return true;
                }))
                .exceptionally(t -> false);
    }

    private void load() {
        if (! Files.exists(file))
            return;
        try {
            CborObject.CborMap map = (CborObject.CborMap) CborObject.fromByteArray(Files.readAllBytes(file));
            Optional<List<Cid>> savedIds = map.getOptional("ids", c -> ((CborObject.CborList) c).value.stream()
                    .map(id -> Cid.decodePeerId(((CborObject.CborString) id).value))
                    .collect(Collectors.toList()));
            if (savedIds.isPresent() && ! savedIds.get().isEmpty())
                ids = savedIds.get();
            props = map.getOptional("props", BlockStoreProperties::fromCbor).orElse(null);
        } catch (Throwable t) {
            // a file we cannot read is one we are better off without
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {}
        }
    }

    private synchronized void save() {
        SortedMap<String, Cborable> state = new TreeMap<>();
        List<Cid> currentIds = ids;
        if (currentIds != null)
            state.put("ids", new CborObject.CborList(currentIds.stream()
                    .map(id -> new CborObject.CborString(id.toString()))
                    .collect(Collectors.toList())));
        BlockStoreProperties currentProps = props;
        if (currentProps != null)
            state.put("props", currentProps);
        try {
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, CborObject.CborMap.build(state).serialize());
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // next start asks the server, as before
        }
    }
}
