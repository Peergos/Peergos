package peergos.server.storage;

import io.libp2p.core.*;
import io.libp2p.core.crypto.*;

import java.util.*;

public interface ServerIdentityStore {

    List<PeerId> getIdentities();

    void addIdentity(PrivKey privateKey, byte[] signedIpnsRecord);

    byte[] getPrivateKey(PeerId peerId);

    byte[] getRecord(PeerId peerId);

    void setRecord(PeerId peerId, byte[] newRecord);
}
