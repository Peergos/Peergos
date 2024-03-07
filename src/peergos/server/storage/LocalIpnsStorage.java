package peergos.server.storage;

import io.libp2p.core.*;
import org.peergos.protocol.ipns.pb.*;
import org.peergos.util.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.storage.*;

import java.nio.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

public class LocalIpnsStorage extends DelegatingDeletableStorage {

    private final ServerIdentityStore ids;
    private final List<Multihash> localIds;

    public LocalIpnsStorage(DeletableContentAddressedStorage target, ServerIdentityStore ids) {
        super(target);
        this.ids = ids;
        this.localIds = ids.getIdentities().stream()
                .map(PeerId::getBytes)
                .map(Multihash::decode)
                .collect(Collectors.toList());
    }

    @Override
    public CompletableFuture<IpnsEntry> getIpnsEntry(Multihash signer) {
        if (localIds.contains(signer)) {
            try {
                Ipns.IpnsEntry proto = Ipns.IpnsEntry.parseFrom(ByteBuffer.wrap(ids.getRecord(new PeerId(signer.toBytes()))));
                return Futures.of(new IpnsEntry(proto.getSignatureV2().toByteArray(), proto.getData().toByteArray()));
            } catch (Exception e) {
                return CompletableFuture.failedFuture(e);
            }
        }
        return super.getIpnsEntry(signer);
    }
}
