package peergos.server.tests.fips203.key.gen.mlkem;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Before;
import org.junit.Test;
import peergos.server.crypto.asymmetric.mlkem.fips203.ParameterSet;
import peergos.server.crypto.asymmetric.mlkem.fips203.key.KeyPair;
import peergos.server.tests.fips203.harness.TestCase;
import peergos.server.tests.fips203.harness.TestGroup;
import peergos.server.tests.fips203.harness.TestPrompt;
import peergos.server.crypto.asymmetric.mlkem.fips203.key.gen.mlkem.MLKEMKeyPairGenerator;

import java.io.IOException;
import java.io.InputStream;
import java.util.HexFormat;
import java.util.Objects;

import static org.junit.Assert.assertEquals;

public class MLKEMKeyGeneratorTests {

    private TestPrompt prompt;

    private TestPrompt loadTestPrompt(String fromResource) throws IOException {

        // Create an ObjectMapper instance
        var objectMapper = new ObjectMapper();

        // Load the JSON test prompts
        try (InputStream inputStream = MLKEMKeyGeneratorTests.class.getResourceAsStream(fromResource)) {
            if (inputStream == null) {
                throw new IOException("Could not find " + fromResource);
            }

            // Deserialize JSON into POJO
            return objectMapper.readValue(inputStream, TestPrompt.class);
        }
    }

    @Before
    public void setUpTest() throws IOException {

        prompt = loadTestPrompt("internalProjection.json");

    }

    private void execTestCase(ParameterSet params, TestCase testCase) {

        // Create keygen under test
        MLKEMKeyPairGenerator mlKemKeyGen = MLKEMKeyPairGenerator.create(params);

        // Print header
        System.out.printf("%n[Test Case %d] using %s Parameter Set:%n", testCase.getTcId(), params.getName());

        // Inputs
        byte[] inputD = HexFormat.of().parseHex((String) testCase.getValues().get("d"));
        byte[] inputZ = HexFormat.of().parseHex((String) testCase.getValues().get("z"));

        // Outputs
        byte[] expectedEK = HexFormat.of().parseHex((String) testCase.getValues().get("ek"));
        byte[] expectedDK = HexFormat.of().parseHex((String) testCase.getValues().get("dk"));

        // Generate the internal KeyPair
        KeyPair keyPair = mlKemKeyGen.generateKeyPair(inputD, inputZ);

        // Extract the encaps key
        byte[] ek = keyPair.getEncapsulationKey().getBytes();

        // Verify it is the expected length
        assertEquals(params.getEncapsulationKeyLength(), ek.length);

        // Extract the decaps key
        byte[] dk = keyPair.getDecapsulationKey().getBytes();

        // Verify it is the expected length
        assertEquals(params.getDecapsulationKeyLength(), dk.length);

        // Iterate through each byte and validate they are the same
        System.out.printf(" -- EK%n");
        System.out.printf("   --> Expect: %s%n", HexFormat.of().formatHex(expectedEK));
        System.out.printf("   --> Actual: %s%n", HexFormat.of().formatHex(ek));
        for (int i = 0; i < params.getEncapsulationKeyLength(); i++) {
            assertEquals(expectedEK[i], ek[i]);
        }

        // Iterate through each byte and validate they are the same
        System.out.printf(" -- DK%n");
        System.out.printf("   --> Expect: %s%n", HexFormat.of().formatHex(expectedDK));
        System.out.printf("   --> Actual: %s%n", HexFormat.of().formatHex(dk));
        for (int i = 0; i < params.getEncapsulationKeyLength(); i++) {
            assertEquals(expectedDK[i], dk[i]);
        }
    }

    @Test
    public void mlKem512KeyGenTest() {

        ParameterSet params = ParameterSet.ML_KEM_512;

        for (TestGroup testGroup: prompt.getTestGroups()) {
            if (Objects.equals(testGroup.getParameterSet(), params.getName())) {
                System.out.printf("Group %d using %s Parameter Set:%n", testGroup.getTgId(), params.getName());
                for (TestCase testCase: testGroup.getTests()) {
                    execTestCase(params, testCase);
                }
            }
        }

    }

    @Test
    public void mlKem768KeyGenTest() {

        ParameterSet params = ParameterSet.ML_KEM_768;

        for (TestGroup testGroup: prompt.getTestGroups()) {
            if (Objects.equals(testGroup.getParameterSet(), params.getName())) {
                System.out.printf("Group %d using %s Parameter Set:%n", testGroup.getTgId(), params.getName());
                for (TestCase testCase: testGroup.getTests()) {
                    execTestCase(params, testCase);
                }
            }
        }

    }

    @Test
    public void mlKem1024KeyGenTest() {

        ParameterSet params = ParameterSet.ML_KEM_1024;

        for (TestGroup testGroup: prompt.getTestGroups()) {
            if (Objects.equals(testGroup.getParameterSet(), params.getName())) {
                System.out.printf("Group %d using %s Parameter Set:%n", testGroup.getTgId(), params.getName());
                for (TestCase testCase: testGroup.getTests()) {
                    execTestCase(params, testCase);
                }
            }
        }

    }

}
