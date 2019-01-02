package peergos.shared.social;

public class FollowRequestWithCipherText {

    public final FollowRequest req;
    public final BlindFollowRequest cipher;

    public FollowRequestWithCipherText(FollowRequest req, BlindFollowRequest cipher) {
        this.req = req;
        this.cipher = cipher;
    }
}
