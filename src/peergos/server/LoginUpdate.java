package peergos.server;

import peergos.server.util.Args;
import peergos.shared.Crypto;
import peergos.shared.MaybeMultihash;
import peergos.shared.NetworkAccess;
import peergos.shared.cbor.CborObject;
import peergos.shared.corenode.OpLog;
import peergos.shared.crypto.BoxingKeyPair;
import peergos.shared.crypto.SigningKeyPair;
import peergos.shared.crypto.SigningPrivateKeyAndPublicHash;
import peergos.shared.crypto.asymmetric.PublicSigningKey;
import peergos.shared.crypto.hash.PublicKeyHash;
import peergos.shared.crypto.symmetric.SymmetricKey;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.mutable.PointerUpdate;
import peergos.shared.storage.ContentAddressedStorage;
import peergos.shared.storage.TransactionId;
import peergos.shared.user.*;
import peergos.shared.user.fs.AbsoluteCapability;
import peergos.shared.user.fs.WritableAbsoluteCapability;
import peergos.shared.util.ArrayOps;
import peergos.shared.util.Pair;

import java.net.URL;
import java.util.List;
import java.util.Optional;

public class LoginUpdate {

    public static void main(String[] a) throws Exception {
        Args args = Args.parse(a);
        Crypto crypto = JavaCrypto.init();
        String signerHex = args.getArg("signer");
        SigningPrivateKeyAndPublicHash identity = SigningPrivateKeyAndPublicHash.fromCbor(CborObject.fromByteArray(ArrayOps.hexToBytes(signerHex)));
        String homeCapLink = args.getArg("home-cap");
        AbsoluteCapability home = WritableAbsoluteCapability.fromLink(homeCapLink);
        String boxerHex = args.getArg("boxer");
        BoxingKeyPair boxer = BoxingKeyPair.fromCbor(CborObject.fromByteArray(ArrayOps.hexToBytes(boxerHex)));
        NetworkAccess network = Builder.buildJavaNetworkAccess(new URL("https://peergos.net"), true, Optional.of("Peergos-" + UserService.CURRENT_VERSION + "-login"), Optional.empty()).get();
        String username = args.getArg("username");
        PublicKeyHash remoteId = network.coreNode.getPublicKeyHash(username).join().get();
        PublicKeyHash idHash = identity.publicKeyHash;
        if (! idHash.equals(remoteId))
            throw new IllegalStateException("Supplied identity doesn't match remote! " + idHash + " != " + remoteId);

        PointerUpdate currentPointer = network.mutable.getPointerTarget(idHash, idHash, network.dhtClient).join();
        WriterData wd = WriterData.getWriterData(idHash, idHash, network.mutable, network.dhtClient).join().props.get();

        byte[] boxerSha256 = crypto.hasher.sha256(boxer.publicBoxingKey.serialize()).join();
        byte[] signedBoxerSha256 = identity.secret.signMessage(boxerSha256).join();
        PublicKeyHash boxerHash = network.dhtClient.putBoxingKey(idHash, signedBoxerSha256, boxer.publicBoxingKey, new TransactionId("12345")).join();

        SigningPrivateKeyAndPublicHash signer = new SigningPrivateKeyAndPublicHash(idHash, identity.secret);
        SecretGenerationAlgorithm alg = wd.generationAlgorithm.get();
        String password = new String(System.console().readPassword("Enter password: "));
        UserWithRoot loginAuth = UserUtil.generateUser(username, password, crypto, alg).join();
        SymmetricKey rootKey = loginAuth.getRoot();
        System.out.println("Setting login data");
        EntryPoint entryPoint = new EntryPoint(home, username);
        PublicSigningKey idPub = network.dhtClient.getSigningKey(idHash, idHash).join().get();
        SigningKeyPair idPair = new SigningKeyPair(idPub, identity.secret);
        SigningKeyPair loginSigner = loginAuth.getUser();
        UserStaticData entryPoints = new UserStaticData(List.of(entryPoint), rootKey, Optional.of(idPair), Optional.of(boxer));

        if (! boxerHash.equals(wd.followRequestReceiver.get())) {
            System.out.println("Supplied social keypair doesn't match remote: " + boxerHash + " != " + wd.followRequestReceiver.get());
            System.out.println("Updating to supplied social keypair and updating login data...");
            byte[] signedBoxerHash = identity.secret.signMessage(boxerSha256).join();
            PublicKeyHash kh = network.dhtClient.putBoxingKey(identity.publicKeyHash, signedBoxerHash, boxer.publicBoxingKey, new TransactionId("12345")).join();
            WriterData updatedWd = wd.withBoxer(Optional.of(kh));
            byte[] rawWd = updatedWd.serialize();
            Cid blockHash = crypto.hasher.hash(rawWd, false).join();
            byte[] signedHash = identity.secret.signMessage(blockHash.getHash()).join();
            OpLog.BlockWrite blockWrite = new OpLog.BlockWrite(identity.publicKeyHash, signedHash, rawWd, false, Optional.empty());
            PointerUpdate pointerCas = new PointerUpdate(currentPointer.updated, MaybeMultihash.of(blockHash), PointerUpdate.increment(currentPointer.sequence));
            byte[] signedPointer = identity.secret.signMessage(pointerCas.serialize()).join();
            OpLog.PointerWrite pointerWrite = new OpLog.PointerWrite(identity.publicKeyHash, signedPointer);
            LoginData newLoginData = new LoginData(username, entryPoints, loginSigner.publicSigningKey, Optional.of(new Pair<>(blockWrite, pointerWrite)));
            network.account.setLoginData(newLoginData, identity, false).join();
        } else {
            LoginData login = new LoginData(username, entryPoints, loginSigner.publicSigningKey, Optional.empty());
            network.account.setLoginData(login, signer, false).join();
        }
        System.out.println("Completed update");
    }
}
