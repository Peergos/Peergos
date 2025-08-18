package peergos.server;

import peergos.server.util.Args;
import peergos.shared.Crypto;
import peergos.shared.NetworkAccess;
import peergos.shared.cbor.CborObject;
import peergos.shared.crypto.BoxingKeyPair;
import peergos.shared.crypto.SigningKeyPair;
import peergos.shared.crypto.SigningPrivateKeyAndPublicHash;
import peergos.shared.crypto.asymmetric.PublicSigningKey;
import peergos.shared.crypto.hash.PublicKeyHash;
import peergos.shared.crypto.symmetric.SymmetricKey;
import peergos.shared.storage.ContentAddressedStorage;
import peergos.shared.storage.TransactionId;
import peergos.shared.user.*;
import peergos.shared.user.fs.AbsoluteCapability;
import peergos.shared.user.fs.WritableAbsoluteCapability;
import peergos.shared.util.ArrayOps;

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
        NetworkAccess network = Builder.buildJavaNetworkAccess(new URL("https://peergos.net"), true, Optional.of("Peergos-" + UserService.CURRENT_VERSION + "-login")).get();
        String username = args.getArg("username");
        PublicKeyHash remoteId = network.coreNode.getPublicKeyHash(username).join().get();
        PublicKeyHash idHash = identity.publicKeyHash;
        if (! idHash.equals(remoteId))
            throw new IllegalStateException("Supplied identity doesn't match remote! " + idHash + " != " + remoteId);

        WriterData wd = WriterData.getWriterData(idHash, idHash, network.mutable, network.dhtClient).join().props.get();

        byte[] boxerSha256 = crypto.hasher.sha256(boxer.publicBoxingKey.serialize()).join();
        byte[] signedBoxerSha256 = identity.secret.signMessage(boxerSha256).join();
        PublicKeyHash boxerHash = network.dhtClient.putBoxingKey(idHash, signedBoxerSha256, boxer.publicBoxingKey, new TransactionId("12345")).join();
        if (! boxerHash.equals(wd.followRequestReceiver.get()))
            throw new IllegalStateException("Supplied social keypair doesn't match remote! " + boxerHash + " != " + wd.followRequestReceiver.get());

        SigningPrivateKeyAndPublicHash signer = new SigningPrivateKeyAndPublicHash(idHash, identity.secret);
        SecretGenerationAlgorithm alg = wd.generationAlgorithm.get();
        String password = new String(System.console().readPassword("Enter password: "));
        UserWithRoot loginAuth = UserUtil.generateUser(username, password, crypto, alg).join();
        SymmetricKey rootKey = loginAuth.getRoot();
        System.out.println("Setting login data");
        EntryPoint entryPoint = new EntryPoint(home, username);
        PublicSigningKey idPub = network.dhtClient.getSigningKey(idHash, idHash).join().get();
        SigningKeyPair idPair = new SigningKeyPair(idPub, identity.secret);
        UserStaticData entryPoints = new UserStaticData(List.of(entryPoint), rootKey, Optional.of(idPair), Optional.of(boxer));
        SigningKeyPair loginSigner = loginAuth.getUser();
        LoginData login = new LoginData(username, entryPoints, loginSigner.publicSigningKey, Optional.empty());
        network.account.setLoginData(login, signer).join();
        System.out.println("Completed update");
    }
}
