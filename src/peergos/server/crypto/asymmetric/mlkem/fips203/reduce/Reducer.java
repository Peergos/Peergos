package peergos.server.crypto.asymmetric.mlkem.fips203.reduce;

public interface Reducer {

    int reduce(int a) throws ReductionException;

}
