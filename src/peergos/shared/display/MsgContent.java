package peergos.shared.display;

import peergos.shared.cbor.*;

public interface MsgContent extends Cborable {

    static MsgContent fromCbor(Cborable cbor) {
        if (cbor instanceof CborObject.CborString)
            return new Text(((CborObject.CborString) cbor).value);
        throw new IllegalStateException("Unknown MsgContent type!");
    }
}
