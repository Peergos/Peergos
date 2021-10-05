
package peergos.shared.user;

import peergos.shared.cbor.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.util.*;

import java.io.*;
import java.util.concurrent.*;

public class HttpAccount implements Account {

    private final HttpPoster direct;

    public HttpAccount(HttpPoster direct) {
        this.direct = direct;
    }
   
    @Override
    public CompletableFuture<Boolean> setLoginData(LoginData login, byte[] auth) {
        return direct.postUnzip(Constants.LOGIN_URL + "setLogin?username=" + login.username + "&auth=" + ArrayOps.bytesToHex(auth), login.serialize()).thenApply(res -> {
            DataInputStream din = new DataInputStream(new ByteArrayInputStream(res));
            try {
                return din.readBoolean();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public CompletableFuture<UserStaticData> getLoginData(String username, PublicSigningKey authorisedReader, byte[] auth) {
        return direct.get(Constants.LOGIN_URL + "getLogin?username=" + username + "&author=" + ArrayOps.bytesToHex(authorisedReader.serialize()) + "&auth=" + ArrayOps.bytesToHex(auth))
                .thenApply(res -> UserStaticData.fromCbor(CborObject.fromByteArray(res)));
    }
}
