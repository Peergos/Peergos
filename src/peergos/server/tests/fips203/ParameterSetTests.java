package peergos.server.tests.fips203;

import org.junit.Test;
import peergos.server.crypto.asymmetric.mlkem.fips203.ParameterSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class ParameterSetTests {

    @Test
    public void testForHiddenParamSets() {
        for (ParameterSet params : ParameterSet.values()) {
            switch (params) {
                case ML_KEM_512, ML_KEM_768, ML_KEM_1024 -> {
                    // These are expected
                }
                default -> throw new IllegalStateException("Unexpected value: " + params);
            }
        }
    }

    @Test
    public void testMLKEM512ParameterSet() {

        ParameterSet params = ParameterSet.ML_KEM_512;

        assertNotNull(params);
        assertEquals("ML-KEM-512", params.getName());
        assertEquals(256, params.getN());
        assertEquals(3329, params.getQ());
        assertEquals(2, params.getK());
        assertEquals(3, params.getEta1());
        assertEquals(2, params.getEta2());
        assertEquals(10, params.getDu());
        assertEquals(4, params.getDv());
        assertEquals(128, params.getMinSecurityStrength());
        assertEquals(800, params.getEncapsulationKeyLength());
        assertEquals(1632, params.getDecapsulationKeyLength());
        assertEquals(768, params.getCiphertextLength());
        assertEquals(32, params.getSharedSecretKeyLength());

    }

    @Test
    public void testMLKEM768ParameterSet() {

        ParameterSet params = ParameterSet.ML_KEM_768;

        assertNotNull(params);
        assertEquals("ML-KEM-768", params.getName());
        assertEquals(256, params.getN());
        assertEquals(3329, params.getQ());
        assertEquals(3, params.getK());
        assertEquals(2, params.getEta1());
        assertEquals(2, params.getEta2());
        assertEquals(10, params.getDu());
        assertEquals(4, params.getDv());
        assertEquals(192, params.getMinSecurityStrength());
        assertEquals(1184, params.getEncapsulationKeyLength());
        assertEquals(2400, params.getDecapsulationKeyLength());
        assertEquals(1088, params.getCiphertextLength());
        assertEquals(32, params.getSharedSecretKeyLength());

    }

    @Test
    public void testMLKEM1024ParameterSet() {

        ParameterSet params = ParameterSet.ML_KEM_1024;

        assertNotNull(params);
        assertEquals("ML-KEM-1024", params.getName());
        assertEquals(256, params.getN());
        assertEquals(3329, params.getQ());
        assertEquals(4, params.getK());
        assertEquals(2, params.getEta1());
        assertEquals(2, params.getEta2());
        assertEquals(11, params.getDu());
        assertEquals(5, params.getDv());
        assertEquals(256, params.getMinSecurityStrength());
        assertEquals(1568, params.getEncapsulationKeyLength());
        assertEquals(3168, params.getDecapsulationKeyLength());
        assertEquals(1568, params.getCiphertextLength());
        assertEquals(32, params.getSharedSecretKeyLength());

    }

}
