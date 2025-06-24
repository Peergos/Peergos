package peergos.server.crypto.asymmetric.mlkem.fips203.key;

public interface Key {

    byte[] getBytes();

    /**
     * Any class which implements this method must zero out memory containing key values.
     */
    void destroy();

}
