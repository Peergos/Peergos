package peergos.server.crypto.asymmetric.mlkem.fips202;

import peergos.server.crypto.asymmetric.mlkem.fips202.keccak.MimicloneKeccak;

public class MimicloneFIPS202 implements FIPS202 {

    @Override
    public MimicloneKeccak keccakPermutation(MimicloneKeccak.Permutation permutation) {

        return new MimicloneKeccak(permutation);

    }

}
