package peergos.shared.storage;

import jsinterop.annotations.*;

@JsType
public class DecodedSpaceRequest {
    public final QuotaControl.LabelledSignedSpaceRequest source;
    public final QuotaControl.SpaceRequest decoded;

    public DecodedSpaceRequest(QuotaControl.LabelledSignedSpaceRequest source, QuotaControl.SpaceRequest decoded) {
        this.source = source;
        this.decoded = decoded;
    }

    @JsMethod
    public String getUsername() {
        return source.getUsername();
    }

    @JsMethod
    public int getSizeInMiB() {
        return (int) (decoded.getSizeInBytes() / (1024 * 1024));
    }
}
