package peergos.storage.dht;

import peergos.storage.net.HttpMessenger;
import peergos.util.*;

import java.io.IOException;
import java.util.concurrent.*;
import java.util.function.*;

public class PeergosDHT implements DHT
{
    private Router router;

    public PeergosDHT(Router router)
    {
        this.router = router;
    }

    public static class PutHandler implements Function<Object, PutOffer> {
        private final byte[] key, value;
        private final Router router;

        public PutHandler(Router router, byte[] key, byte[] value) {
            this.key = key;
            this.value = value;
            this.router = router;
        }

        public final PutOffer apply(Object obj)
        {
            PutOffer offer = (PutOffer) obj;
                if (offer.getTarget().external.equals(router.address().external)) {
                    if (router.storage.isWaitingFor(key))
                        router.storage.put(new ByteArrayWrapper(key), value);
                } else try {
                    HttpMessenger.putFragment(offer.getTarget().external, "/" + ArrayOps.bytesToHex(key), value);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                return offer;
        }
    }

    public static class GetHandler implements Function<Object, GetOffer>{
        private final byte[] key;
        private final Router router;

        public GetHandler(Router router, byte[] key) {
            this.key = key;
            this.router = router;
        }

        public final GetOffer apply(Object obj)
        {
            GetOffer offer = (GetOffer) obj;
            if (offer.getTarget().external.equals(router.address().external))
                offer.target = new NodeID(offer.target.id, offer.getTarget().external);

            return offer;
        }
    }

    // 256 bit key / 32 byte
    public CompletableFuture<PutOffer> put(byte[] key, byte[] value, byte[] owner, byte[] sharingKey, byte[] mapKey, byte[] proof)
    {
        assert(key.length == 32);
        CompletableFuture<Object> fut = router.ask(new Message.PUT(key, value.length, owner, sharingKey, mapKey, proof));
        return fut.thenApply(new PutHandler(router, key, value));
    }

    public CompletableFuture<GetOffer> contains(byte[] key)
    {
        assert(key.length == 32);
        CompletableFuture<Object> fut = router.ask(new Message.GET(key));
        return fut.thenApply(new GetHandler(router, key));
    }

    public CompletableFuture<GetOffer> get(byte[] key)
    {
        assert(key.length == 32);
        CompletableFuture<Object> fut = router.ask(new Message.GET(key));
        return fut.thenApply(new GetHandler(router, key));
    }
}