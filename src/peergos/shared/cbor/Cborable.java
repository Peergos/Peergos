package peergos.shared.cbor;

import jsinterop.annotations.JsType;

import java.util.function.*;

@JsType
public interface Cborable {

    CborObject toCbor();

    default byte[] serialize() {
        return toCbor().toByteArray();
    }

    static <T> Function<byte[], T> parser(Function<Cborable, T> parser) {
        return arr -> parser.apply(CborObject.fromByteArray(arr));
    }
}
