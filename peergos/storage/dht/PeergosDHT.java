package peergos.storage.dht;

import peergos.crypto.*;
import peergos.storage.net.HttpMessenger;
import peergos.util.*;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

public class PeergosDHT implements DHT
{
    private Router router;

    public PeergosDHT(Router router)
    {
        this.router = router;
    }

    public static class PutHandler implements Function<Object, Optional<byte[]>> {
        private final byte[] key, value;
        private final Router router;

        public PutHandler(Router router, byte[] value) {
            this.key = UserPublicKey.hash(value);
            this.value = value;
            this.router = router;
        }

        public final Optional<byte[]> apply(Object obj)
        {
            PutOffer offer = (PutOffer) obj;
                if (offer.getTarget().external.equals(router.address().external)) {
                    if (router.storage.isWaitingFor(key))
                        if (router.storage.put(new ByteArrayWrapper(key), value))
                            return Optional.of(key);
                } else try {
                    HttpMessenger.putFragment(offer.getTarget().external, "/" + ArrayOps.bytesToHex(key), value);
                    return Optional.of(key);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                return Optional.empty();
        }
    }

    public static class GetHandler implements Function<Object, byte[]>{
        private final byte[] key;
        private final Router router;

        public GetHandler(Router router, byte[] key) {
            this.key = key;
            this.router = router;
        }

        public final byte[] apply(Object obj)
        {
            GetOffer offer = (GetOffer) obj;
            if (offer.getTarget().external.equals(router.address().external))
                offer.target = new NodeID(offer.target.id, offer.getTarget().external);

            try {
                return HttpMessenger.getFragment(offer.getTarget().external, "/" + ArrayOps.bytesToHex(key));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public static class ContainsHandler implements Function<Object, Integer>{
        private final byte[] key;
        private final Router router;

        public ContainsHandler(Router router, byte[] key) {
            this.key = key;
            this.router = router;
        }

        public final Integer apply(Object obj)
        {
            GetOffer offer = (GetOffer) obj;
            if (offer.getTarget().external.equals(router.address().external))
                offer.target = new NodeID(offer.target.id, offer.getTarget().external);

            return offer.getSize();
        }
    }

    public CompletableFuture<Optional<byte[]>> put(byte[] value, byte[] owner, byte[] sharingKey, byte[] mapKey, byte[] proof)
    {
        PutHandler handler = new PutHandler(router, value);
        CompletableFuture<Object> fut = router.ask(new Message.PUT(handler.key, value.length, owner, sharingKey, mapKey, proof));
        return fut.thenApply(handler);
    }

    public CompletableFuture<Integer> contains(byte[] key)
    {
        assert(key.length == 32);
        CompletableFuture<Object> fut = router.ask(new Message.GET(key));
        return fut.thenApply(new ContainsHandler(router, key));
    }

    public CompletableFuture<byte[]> get(byte[] key)
    {
        assert(key.length == 32);
        CompletableFuture<Object> fut = router.ask(new Message.GET(key));
        return fut.thenApply(new GetHandler(router, key));
    }
}