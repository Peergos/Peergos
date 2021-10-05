package peergos.server.login;

import peergos.shared.corenode.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.mutable.*;
import peergos.shared.storage.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.util.concurrent.*;

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
    public CompletableFuture<UserStaticData> getLoginData(String username, PublicSigningKey authorisedReader, byte[] auth) {
        return target.getEntryData(username);
    }
}
