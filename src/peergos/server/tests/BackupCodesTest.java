package peergos.server.tests;

import org.junit.*;
import peergos.shared.cbor.*;
import peergos.shared.login.mfa.*;

import java.security.*;
import java.util.*;
import java.util.stream.*;

public class BackupCodesTest {

    @Test
    public void codeAlphabet() {
        SecureRandom rnd = new SecureRandom();
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            byte[] random = new byte[BackupCodes.CODE_BYTES];
            rnd.nextBytes(random);
            String code = BackupCodes.generate(random);
            Assert.assertEquals(BackupCodes.CODE_CHARS, code.length());
            Assert.assertTrue(code, code.matches("[a-z2-7]+"));
            codes.add(code);
        }
        Assert.assertEquals(1000, codes.size());
    }

    @Test
    public void normalisation() {
        String code = "a3f5b2xqz7";
        Assert.assertEquals("a3f5b-2xqz7", BackupCodes.format(code));
        Assert.assertEquals(code, BackupCodes.normalise(code));
        Assert.assertEquals(code, BackupCodes.normalise(BackupCodes.format(code)));
        Assert.assertEquals(code, BackupCodes.normalise("A3F5B-2XQZ7"));
        Assert.assertEquals(code, BackupCodes.normalise(" a3f5b 2xqz7 "));
        Assert.assertEquals(code, BackupCodes.normalise("a3f5b_2xqz7"));
    }

    @Test
    public void cborRoundtrip() {
        byte[] credentialId = new byte[32];
        new Random(42).nextBytes(credentialId);
        List<String> codes = IntStream.range(0, BackupCodes.CODE_COUNT)
                .mapToObj(i -> BackupCodes.generate(new byte[]{(byte) i, 1, 2, 3, 4, 5, 6}))
                .collect(Collectors.toList());
        BackupCodes original = new BackupCodes(credentialId, codes);

        BackupCodes decoded = BackupCodes.fromCbor(CborObject.fromByteArray(original.serialize()));
        Assert.assertArrayEquals(credentialId, decoded.credentialId);
        Assert.assertEquals(codes, decoded.codes);
        Assert.assertEquals(codes.stream().map(BackupCodes::format).collect(Collectors.toList()), decoded.formatted());
    }
}
