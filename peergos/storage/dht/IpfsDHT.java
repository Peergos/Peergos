package peergos.storage.dht;

import org.ipfs.*;

import java.util.concurrent.*;

public class IpfsDHT implements DHT {
    private final IPFS ipfs;

    public IpfsDHT(IPFS ipfs) {
        this.ipfs = ipfs;
    }

    public CompletableFuture<Boolean> put(byte[] key, byte[] value, byte[] owner, byte[] writingKey, byte[] mapKey, byte[] proof) {
        throw new IllegalStateException("Unimplemented!");
    }

    public CompletableFuture<Integer> contains(byte[] key) {
        throw new IllegalStateException("Unimplemented!");
    }

    public CompletableFuture<byte[]> get(byte[] key) {
        throw new IllegalStateException("Unimplemented!");
    }
}
