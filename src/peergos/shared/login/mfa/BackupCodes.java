package peergos.shared.login.mfa;

import jsinterop.annotations.JsType;
import peergos.shared.cbor.*;
import peergos.shared.io.ipfs.bases.Base32;

import java.util.*;
import java.util.stream.*;

/** A set of single use codes which can be used as a second auth factor if a user loses their
 *  authenticator app or security key. The plaintext codes are only ever available at generation
 *  time - the server stores only their hashes.
 */
@JsType
public class BackupCodes implements Cborable {

    public static final int CODE_COUNT = 10;
    public static final int CODE_CHARS = 10;
    /** enough random bytes to base32 encode to at least CODE_CHARS characters */
    public static final int CODE_BYTES = 7;
    private static final int GROUP = 5;

    public final byte[] credentialId;
    public final List<String> codes;

    public BackupCodes(byte[] credentialId, List<String> codes) {
        this.credentialId = credentialId;
        this.codes = codes;
    }

    /** Display form of a code, e.g. "a3f5b-2xqz7"
     */
    public static String format(String code) {
        StringBuilder res = new StringBuilder();
        for (int i = 0; i < code.length(); i += GROUP) {
            if (i > 0)
                res.append("-");
            res.append(code, i, Math.min(i + GROUP, code.length()));
        }
        return res.toString();
    }

    public List<String> formatted() {
        return codes.stream().map(BackupCodes::format).collect(Collectors.toList());
    }

    /** Users retype or paste codes with the grouping separator, spaces, or in upper case, so
     *  reduce to the canonical form before hashing or comparing.
     */
    public static String normalise(String code) {
        StringBuilder res = new StringBuilder();
        String lower = code.toLowerCase();
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '2' && c <= '7'))
                res.append(c);
        }
        return res.toString();
    }

    public static String generate(byte[] random) {
        if (random.length < CODE_BYTES)
            throw new IllegalStateException("Insufficient randomness for a backup code!");
        String base32 = new Base32().encodeToString(random).replaceAll("=", "").toLowerCase();
        return base32.substring(0, CODE_CHARS);
    }

    @Override
    public CborObject toCbor() {
        SortedMap<String, Cborable> state = new TreeMap<>();
        state.put("i", new CborObject.CborByteArray(credentialId));
        state.put("c", new CborObject.CborList(codes.stream()
                .map(CborObject.CborString::new)
                .collect(Collectors.toList())));
        return CborObject.CborMap.build(state);
    }

    public static BackupCodes fromCbor(Cborable cbor) {
        if (!(cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor for BackupCodes! " + cbor);
        CborObject.CborMap m = (CborObject.CborMap) cbor;
        return new BackupCodes(m.getByteArray("i"), m.getList("c", c -> ((CborObject.CborString) c).value));
    }
}
