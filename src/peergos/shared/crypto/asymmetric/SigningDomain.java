package peergos.shared.crypto.asymmetric;

/** What a signature is for. Composite keys sign the domain along with the message, so a signature made for one
 *  purpose can't be passed off as another, e.g. a signed block hash as a signed boxing key hash.
 *  Ed25519 keys ignore it, which keeps their existing signatures valid.
 */
public enum SigningDomain {
    POINTER("pointer"),
    BLOCK("block"),
    BLOCK_WRITE_AUTH("block-write-auth"),
    BLOCK_LIST("block-list"),
    OWNER_PROOF("owner-proof"),
    SIGNING_KEY("signing-key"),
    BOXING_KEY("boxing-key"),
    KEY_CHANGE("key-change"),
    USERNAME_CLAIM("username-claim"),
    TIME_LIMITED("time-limited-request"),
    SPACE_REQUEST("space-request"),
    SPACE_REQUEST_APPROVAL("space-request-approval"),
    WRITER_QUOTA("writer-quota"),
    SERVER_MESSAGE("server-message"),
    CHAT("chat"),
    IPNS("ipns"),
    LOGIN("login"),
    IDENTITY_LINK("identity-link"),
    FOLLOW_REQUEST_REMOVAL("follow-request-removal");

    public final String label;

    SigningDomain(String label) {
        this.label = label;
    }
}
