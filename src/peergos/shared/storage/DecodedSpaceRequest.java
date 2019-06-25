package peergos.shared.storage;

import jsinterop.annotations.*;

@JsType
public class DecodedSpaceRequest {
    public final SpaceUsage.LabelledSignedSpaceRequest source;
    public final SpaceUsage.SpaceRequest decoded;

    public DecodedSpaceRequest(SpaceUsage.LabelledSignedSpaceRequest source, SpaceUsage.SpaceRequest decoded) {
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
