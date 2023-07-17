package peergos.server.crypto.hash;
import java.util.*;
import java.util.logging.*;

import java.security.*;
import java.util.concurrent.CompletableFuture;

import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.Multihash;
import peergos.server.crypto.hash.lambdaworks.crypto.SCrypt;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import javax.crypto.*;
import javax.crypto.spec.*;

public class ScryptJava implements Hasher {
	private static final Logger LOG = Logger.getGlobal();
    private static final int LOG_2_MIN_RAM = 17;

    @Override
    public CompletableFuture<byte[]> hashToKeyBytes(String username, String password, SecretGenerationAlgorithm algorithm) {
        CompletableFuture<byte[]> res = new CompletableFuture<>();
        if (algorithm.getType() == SecretGenerationAlgorithm.Type.Scrypt) {
            byte[] hash = Hash.sha256(password.getBytes());
            byte[] salt = username.getBytes();
            try {
                ScryptGenerator params = (ScryptGenerator) algorithm;
                long t1 = System.currentTimeMillis();
                int parallelism = params.parallelism;
                int nOutputBytes = params.outputBytes;
                int cpuCost = params.cpuCost;
                int memoryCost = 1 << params.memoryCost; // Amount of ram required to run algorithm in bytes
                byte[] scryptHash = SCrypt.scrypt(hash, salt, memoryCost, cpuCost, parallelism, nOutputBytes);
                long t2 = System.currentTimeMillis();
                LOG.info("Scrypt hashing took: " + (t2 - t1) + " mS");
                res.complete(scryptHash);
                return res;
            } catch (GeneralSecurityException gse) {
                res.completeExceptionally(gse);
            }
            return res;
        }
        throw new IllegalStateException("Unknown user generation algorithm: " + algorithm);
    }

    @Override
    public CompletableFuture<ProofOfWork> generateProofOfWork(int difficulty, byte[] data) {
        byte[] combined = new byte[data.length + ProofOfWork.PREFIX_BYTES];
        System.arraycopy(data, 0, combined, ProofOfWork.PREFIX_BYTES, data.length);
        long counter = 0;
        while (true) {
            byte[] hash = Hash.sha256(combined);
            if (ProofOfWork.satisfiesDifficulty(difficulty, hash)) {
                byte[] prefix = Arrays.copyOfRange(combined, 0, ProofOfWork.PREFIX_BYTES);
                return Futures.of(new ProofOfWork(prefix, Multihash.Type.sha2_256));
            }
            counter++;
            combined[0] = (byte) counter;
            combined[1] = (byte) (counter >> 8);
            combined[2] = (byte) (counter >> 16);
            combined[3] = (byte) (counter >> 24);
            combined[4] = (byte) (counter >> 32);
            combined[5] = (byte) (counter >> 40);
            combined[6] = (byte) (counter >> 48);
            combined[7] = (byte) (counter >> 56);
        }
    }

    @Override
    public CompletableFuture<byte[]> sha256(byte[] input) {
        return CompletableFuture.completedFuture(Hash.sha256(input));
    }

    @Override
    public CompletableFuture<byte[]> hmacSha256(byte[] secretKeyBytes, byte[] message) {
        try {
            String algorithm = "HMACSHA256";
            Mac mac = Mac.getInstance(algorithm);
            SecretKey secretKey = new SecretKeySpec(secretKeyBytes, algorithm);
            mac.init(secretKey);
            return Futures.of(mac.doFinal(message));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public byte[] blake2b(byte[] input, int outputBytes) {
        return Blake2b.Digest.newInstance(outputBytes).digest(input);
    }

    @Override
    public CompletableFuture<Multihash> hashFromStream(AsyncReader stream, long length) {
        return Hash.sha256(stream, length)
                .thenApply(h -> new Multihash(Multihash.Type.sha2_256, h));
    }
}
