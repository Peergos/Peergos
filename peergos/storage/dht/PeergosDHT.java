package peergos.storage.dht;

import peergos.crypto.*;
import peergos.storage.merklebtree.*;
import peergos.storage.net.HttpMessenger;
import peergos.util.*;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

public class PeergosDHT implements ContentAddressedStorage
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
            System.out.println("dht put: "+new ByteArrayWrapper(key));
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
            System.out.println("dht get: "+new ByteArrayWrapper(key));
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

    public byte[] put(byte[] value)
    {
        PutHandler handler = new PutHandler(router, value);
        CompletableFuture<Object> fut = router.ask(new Message.PUT(handler.key, value.length));
        try {
            return fut.thenApply(handler).get().get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public byte[] get(byte[] key)
    {
        System.out.println("dht get start: "+new ByteArrayWrapper(key));
        if (key.length == 0)
            return new byte[0];
        CompletableFuture<Object> fut = router.ask(new Message.GET(key));
        try {
            return fut.thenApply(new GetHandler(router, key)).get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public void remove(byte[] key)
    {
        // Why u nooo remove!
    }
}