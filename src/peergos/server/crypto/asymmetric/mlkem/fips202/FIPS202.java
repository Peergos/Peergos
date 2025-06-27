package peergos.server.crypto.asymmetric.mlkem.fips202;

import peergos.server.crypto.asymmetric.mlkem.fips202.keccak.MimicloneKeccak;

public interface FIPS202 {

    MimicloneKeccak keccakPermutation(MimicloneKeccak.Permutation permutation);

}
