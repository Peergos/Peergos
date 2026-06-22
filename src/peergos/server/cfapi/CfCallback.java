package peergos.server.cfapi;

import java.lang.foreign.MemorySegment;

/** Functional interface matching the CF_CALLBACK signature. */
@FunctionalInterface
public interface CfCallback {
    void invoke(MemorySegment callbackInfo, MemorySegment callbackParameters);
}
