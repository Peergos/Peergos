package peergos.server.login;

import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.login.mfa.*;
import peergos.shared.mutable.*;
import peergos.shared.storage.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;
import java.util.stream.*;

public class AccountWithStorage implements Account {
    private static final Logger LOG = peergos.server.util.Logging.LOG();

    private final ContentAddressedStorage storage;
    private final MutablePointers pointers;
    private final JdbcAccount target;

    public AccountWithStorage(ContentAddressedStorage storage, MutablePointers pointers, JdbcAccount target) {
        this.storage = storage;
        this.pointers = pointers;
        this.target = target;
        // this is the only place that pairs the login table with the pointers it has to stay in step with
        target.setPublishedChecker(this::isPublished);
    }

    /** Has this WriterData been committed? This is the point at which a staged password change becomes
     *  the live one.
     */
    private boolean isPublished(PublicKeyHash writer, Cid target) {
        try {
            Optional<byte[]> current = pointers.getPointer(writer, writer).join();
            if (current.isEmpty())
                return false;
            PointerUpdate pointer = MutablePointers.parsePointerTarget(current.get(), writer, writer, storage).join();
            return pointer.updated.equals(MaybeMultihash.of(target));
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Couldn't check pointer for " + writer, e);
            return false;
        }
    }

    @Override
    public CompletableFuture<Boolean> setLoginData(LoginData login, byte[] auth, boolean forceLocal) {
        if (login.identityUpdate.isPresent()) {
            // This is a password change: the new login data and the new key generation algorithm live in
            // two different stores, which we can't write atomically. Instead we stage the new login data
            // first, and use the pointer update as the single commit point, so that every state we can
            // crash in is one that can still be logged in to - with the old password before the pointer
            // update, and with the new one after it.
            Pair<OpLog.BlockWrite, OpLog.PointerWrite> pair = login.identityUpdate.get();
            OpLog.BlockWrite block = pair.left;
            OpLog.PointerWrite pointer = pair.right;
            TransactionId tid = storage.startTransaction(block.writer).join();
            // an unreferenced block here is harmless, so put it before we stage anything
            Cid newWriterData = storage.put(block.writer, block.writer, block.signature, block.block, tid).join();
            if (! target.setPendingLoginData(login, pointer.writer, newWriterData).join())
                return Futures.of(false);
            if (! pointers.setPointer(pointer.writer, pointer.writer, pointer.writerSignedChampRootCas).join())
                return Futures.of(false);
            // The new algorithm is now published, so from here on the password change has happened, and
            // the remaining steps are cleanup that must not be able to fail it.
            try {
                storage.closeTransaction(pointer.writer, tid).join();
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Failed to close transaction after password change for " + login.username, e);
            }
            try {
                target.setLoginData(login).join();
            } catch (Exception e) {
                // Logins still succeed from the staged login data, and the next write of this user's
                // login data will promote it.
                LOG.log(Level.WARNING, "Failed to store new login data after password change for " + login.username, e);
            }
            return Futures.of(true);
        }
        return target.setLoginData(login);
    }

    @Override
    public CompletableFuture<Either<UserStaticData, MultiFactorAuthRequest>> getLoginData(String username,
                                                                                          PublicSigningKey authorisedReader,
                                                                                          byte[] auth,
                                                                                          Optional<MultiFactorAuthResponse> mfa,
                                                                                          boolean cacheMfaLoginData,
                                                                                          boolean forceProxy,
                                                                                          boolean forceNoCache) {
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
