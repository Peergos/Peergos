package peergos.server.login;

import peergos.shared.cbor.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.login.mfa.*;
import peergos.shared.mutable.*;
import peergos.shared.storage.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

public class AccountWithStorage implements Account {

    private final ContentAddressedStorage storage;
    private final MutablePointers pointers;
    private final JdbcAccount target;

    public AccountWithStorage(ContentAddressedStorage storage, MutablePointers pointers, JdbcAccount target) {
        this.storage = storage;
        this.pointers = pointers;
        this.target = target;
    }

    @Override
    public CompletableFuture<Boolean> setLoginData(LoginData login, byte[] auth) {
        if (login.identityUpdate.isPresent()) {
            Pair<OpLog.BlockWrite, OpLog.PointerWrite> pair = login.identityUpdate.get();
            OpLog.BlockWrite block = pair.left;
            OpLog.PointerWrite pointer = pair.right;
            TransactionId tid = storage.startTransaction(block.writer).join();
            storage.put(block.writer, block.writer, block.signature, block.block, tid).join();
            pointers.setPointer(pointer.writer, pointer.writer, pointer.writerSignedChampRootCas).join();
            storage.closeTransaction(pointer.writer, tid).join();
        }
        return target.setLoginData(login);
    }

    @Override
    public CompletableFuture<Either<UserStaticData, MultiFactorAuthRequest>> getLoginData(String username,
                                                                                          PublicSigningKey authorisedReader,
                                                                                          byte[] auth,
                                                                                          Optional<MultiFactorAuthResponse> mfa,
                                                                                          boolean cacheMfaLoginData) {
        return target.getEntryData(username, authorisedReader, mfa);
    }

    @Override
    public CompletableFuture<List<MultiFactorAuthMethod>> getSecondAuthMethods(String username, byte[] auth) {
        return target.getSecondAuthMethods(username);
    }

    @Override
    public CompletableFuture<Boolean> enableTotpFactor(String username, byte[] credentialId, String code, byte[] auth) {
        return target.enableTotpFactor(username, credentialId, code);
    }

    @Override
    public CompletableFuture<byte[]> registerSecurityKeyStart(String username, byte[] auth) {
        return Futures.of(target.registerSecurityKeyStart(username));
    }

    @Override
    public CompletableFuture<Boolean> registerSecurityKeyComplete(String username, String keyName, MultiFactorAuthResponse resp, byte[] auth) {
        target.registerSecurityKeyComplete(username, keyName, resp);
        return Futures.of(true);
    }

    @Override
    public CompletableFuture<Boolean> deleteSecondFactor(String username, byte[] credentialId, byte[] auth) {
        return target.deleteMfa(username, credentialId);
    }

    @Override
    public CompletableFuture<TotpKey> addTotpFactor(String username, byte[] auth) {
        return target.addTotpFactor(username);
    }
}
