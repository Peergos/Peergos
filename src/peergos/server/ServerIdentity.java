package peergos.server;

import io.libp2p.core.*;
import io.libp2p.core.crypto.*;
import io.libp2p.crypto.keys.*;
import org.peergos.protocol.ipns.*;
import peergos.server.sql.*;
import peergos.server.storage.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.resolution.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.nio.charset.*;
import java.time.*;
import java.util.*;

public class ServerIdentity extends Builder {
    public static final Command.Arg ARG_SERVERIDS_SQL_FILE =
            new Command.Arg("serverids-file", "The filename for the server ids datastore", false, "serverids.sql");
    public static final Command.Arg ARG_PRIVATE_KEY =
            new Command.Arg("ipfs.identity.priv-key", "Basse64 encoded server identity private key protobuf", true);
    public static final Command<Boolean> GEN_NEXT = new Command<>("gen-next",
            "Generate the next identity of this server. This will allow you to recover if the server is " +
                    "compromised and its identity keypair is stolen. You will be given a password which can be used to " +
                    "regenerate the next server identity and rotate to it.",
            a -> {
                boolean usePostgres = a.getBoolean("use-postgres", false);
                SqlSupplier sqlCommands = usePostgres ?
                        new PostgresCommands() :
                        new SqliteCommands();

                JdbcServerIdentityStore idstore = JdbcServerIdentityStore.build(getDBConnector(a, "serverids-file"), sqlCommands, Main.initCrypto());
                List<PeerId> ids = idstore.getIdentities();
                PeerId current = ids.get(ids.size() - 1);
                byte[] currentRecord = idstore.getRecord(current);
                Optional<IpnsMapping> ipnsMapping = IPNS.parseAndValidateIpnsEntry(
                        ArrayOps.concat("/ipns/".getBytes(StandardCharsets.UTF_8), current.getBytes()),
                        currentRecord);
                if (ipnsMapping.isEmpty())
                    throw new IllegalStateException("Invalid record!");
                ResolutionRecord res = ResolutionRecord.fromCbor(CborObject.fromByteArray(ipnsMapping.get().value.value));
                boolean hasNextId = res.host.isPresent();
                if (hasNextId) {
                    System.out.println("This server has already generated a next identity");
                    return true;
                }
                Crypto crypto = Main.initCrypto();
                String password = Passwords.generate();
                System.out.println("Your password for the next server identity is the following. " +
                        "Please write this down and store it in a safe place. you will need this to recover your server " +
                        "identity after a compromise.");
                System.out.println(password);
                System.out.println("I have written down the password (Y/N)");
                String yes = System.console().readLine();
                if (!yes.equalsIgnoreCase("y")) {
                    System.out.println("Aborting next identity generation");
                    return true;
                }
                System.out.println("Generating next identity...");
                // update ipns record with next peer id
                PrivKey nextPrivate = generateNextIdentity(password, current, crypto);
                PeerId nextPeerId = PeerId.fromPubKey(nextPrivate.publicKey());

                PrivKey currentPrivate = KeyKt.unmarshalPrivateKey(Base64.getDecoder().decode(a.getArg("ipfs.identity.priv-key")));
                idstore.setRecord(current, generateSignedIpnsRecord(currentPrivate, Optional.of(Multihash.decode(nextPeerId.getBytes())), false, res.sequence + 1));
                System.out.println("The next server identity will be " + nextPeerId.toBase58());

                return true;
            },
            Arrays.asList(
                    ARG_SERVERIDS_SQL_FILE,
                    ARG_PRIVATE_KEY
            )
    );

    public static byte[] generateSignedIpnsRecord(PrivKey peerPrivate, Optional<Multihash> host, boolean moved, long sequence) {
        int years = 1;
        LocalDateTime expiry = LocalDateTime.now().plusYears(years);
        long ttlNanos = years * 365L * 24 * 3600 * 1000_000_000;
        ResolutionRecord ipnsValue = new ResolutionRecord(host,
                moved, Optional.empty(), sequence);
        byte[] value = ipnsValue.serialize();
        return IPNS.createSignedRecord(value, expiry, sequence, ttlNanos, peerPrivate);
    }

    public static PrivKey generateNextIdentity(String password, PeerId current, Crypto crypto) {
        SecretGenerationAlgorithm alg = SecretGenerationAlgorithm.getDefaultWithoutExtraSalt();
        byte[] keyBytes = crypto.hasher.hashToKeyBytes(current.toBase58(), password, alg).join();
        byte[] sk = new byte[64];
        byte[] pk = new byte[32];
        System.arraycopy(keyBytes, 0, sk, 32, 32);
        crypto.signer.crypto_sign_keypair(pk, sk);

        // update ipns record with next peer id
        byte[] privateProto = new byte[36];
        System.arraycopy(sk, 32, privateProto, 4, 32);
        // protobuf header
        System.arraycopy(new byte[]{8, 1, 18, 32}, 0, privateProto, 0, 4);
        return Ed25519Kt.unmarshalEd25519PrivateKey(privateProto);
    }

    public static final Command<Boolean> ROTATE = new Command<>("rotate",
            "Rotate the identity of this server. If this server has already generated a next identity," +
                    "you will need the associated password, otherwise a new key pair will be generated.",
            a -> {
                boolean usePostgres = a.getBoolean("use-postgres", false);
                SqlSupplier sqlCommands = usePostgres ?
                        new PostgresCommands() :
                        new SqliteCommands();

                JdbcServerIdentityStore idstore = JdbcServerIdentityStore.build(getDBConnector(a, "serverids-file"), sqlCommands, Main.initCrypto());
                List<PeerId> ids = idstore.getIdentities();
                if (ids.isEmpty())
                    throw new IllegalStateException("Please run Peergos once before trying to rotate the server identity!");
                PeerId current = ids.get(ids.size() - 1);
                byte[] currentRecord = idstore.getRecord(current);
                Optional<IpnsMapping> ipnsMapping = IPNS.parseAndValidateIpnsEntry(
                        ArrayOps.concat("/ipns/".getBytes(StandardCharsets.UTF_8), current.getBytes()),
                        currentRecord);
                if (ipnsMapping.isEmpty())
                    throw new IllegalStateException("Invalid record!");
                ResolutionRecord res = ResolutionRecord.fromCbor(CborObject.fromByteArray(ipnsMapping.get().value.value));
                Crypto crypto = Main.initCrypto();
                PrivKey nextPriv;
                if (res.host.isPresent()) {
                    // require password to regenerate next identity
                    System.out.println("Please enter password for next identity:");
                    String password = System.console().readLine();
                    nextPriv = generateNextIdentity(password, current, crypto);
                } else {
                    // generate a new identity now
                    nextPriv = Ed25519Kt.generateEd25519KeyPair().getFirst();
                }
                PeerId nextPeerId = PeerId.fromPubKey(nextPriv.publicKey());
                PrivKey currentPrivate = KeyKt.unmarshalPrivateKey(Base64.getDecoder().decode(a.getArg("ipfs.identity.priv-key")));
                idstore.setPrivateKey(currentPrivate);
                // update peergos config with new private key
                String encodedPrivate = Base64.getEncoder().encodeToString(nextPriv.bytes());
                boolean useIPFS = a.getBoolean("useIPFS", false);
                a.with("ipfs.identity.priv-key", encodedPrivate)
                        .with("ipfs.identity.peerid", nextPeerId.toBase58())
                        .saveToFile();

                idstore.setRecord(current, generateSignedIpnsRecord(currentPrivate, Optional.of(Multihash.decode(nextPeerId.getBytes())), true, res.sequence + 1));
                idstore.addIdentity(nextPeerId, generateSignedIpnsRecord(nextPriv, Optional.empty(), false, 1));
                System.out.println("Successfully rotated server identity from " + current + " to " + nextPeerId);
                if (!useIPFS) {
                    System.out.println("You are running a multi-server instance, copy the new identity to the ipfs server start script: \n" +
                            "-ipfs.identity.peerid " + nextPeerId.toBase58() +
                            " -ipfs.identity.priv-key " + Base64.getEncoder().encodeToString(nextPriv.bytes()));
                }

                return true;
            },
            Arrays.asList(
                    ARG_SERVERIDS_SQL_FILE,
                    ARG_PRIVATE_KEY
            )
    );

    public static final Command<Boolean> SERVER_IDENTITY = new Command<>("server-identity",
            "Manage the identity of this server",
            args -> {
                System.out.println("Run with -help to show options");
                return null;
            },
            Arrays.asList(),
            Arrays.asList(GEN_NEXT, ROTATE)
    );
}
