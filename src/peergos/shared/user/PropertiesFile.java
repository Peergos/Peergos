package peergos.shared.user;

import peergos.shared.MaybeMultihash;
import peergos.shared.cbor.CborObject;
import peergos.shared.cbor.Cborable;
import peergos.shared.corenode.UserPublicKeyLink;
import peergos.shared.crypto.hash.PublicKeyHash;
import peergos.shared.social.SharedItem;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

public class PropertiesFile implements Cborable {

    private final Map<String, String> props;

    public PropertiesFile(Map<String, String> props) {
        this.props = props;
    }

    public String getStringProperty(String key, String defaultValue) {
        if (!props.containsKey(key))
            return defaultValue;
        return props.get(key);
    }
    public int getIntProperty(String key, int defaultValue) {
        if (!props.containsKey(key))
            return defaultValue;
        return Integer.parseInt(props.get(key));
    }
    public void setStringProperty(String key, String value) {
        props.put(key, value);
    }
    public void setIntProperty(String key, int value) {
        props.put(key, Integer.toString(value));
    }

    public static PropertiesFile empty() {
        return new PropertiesFile(new HashMap<>());
    }

    @Override
    public CborObject toCbor() {
        TreeMap<String, ? extends Cborable> res = props.entrySet()
                .stream()
                .collect(Collectors.toMap(
                        e -> e.getKey(),
                        e -> new CborObject.CborString(e.getValue()),
                        (a,b) -> a,
                        TreeMap::new
                ));
        return CborObject.CborMap.build(res);
    }

    public static PropertiesFile fromCbor(Cborable cbor) {
        if (!(cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor! " + cbor);
        CborObject.CborMap m = (CborObject.CborMap) cbor;
        Map<String, String> props = m.getMap(key -> ((CborObject.CborString) key).value, e -> ((CborObject.CborString) e).value);
        return new PropertiesFile(props);
    }

}
