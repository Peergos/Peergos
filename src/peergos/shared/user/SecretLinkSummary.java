package peergos.shared.user;

import jsinterop.annotations.JsMethod;
import jsinterop.annotations.JsType;
import peergos.shared.crypto.hash.PublicKeyHash;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * One of the owner's secret links, for listing them all in one place.
 *
 * A link is recorded under every path it contains, so the same link is reachable from several
 * files. This is the deduplicated view: one entry per link, whatever it holds.
 */
@JsType
public class SecretLinkSummary {

    public final LinkProperties props;
    /** A path this link was recorded under, used when the link predates member lists. */
    public final String recordedUnder;

    public SecretLinkSummary(LinkProperties props, String recordedUnder) {
        this.props = props;
        this.recordedUnder = recordedUnder;
    }

    @JsMethod
    public LinkProperties getProps() {
        return props;
    }

    @JsMethod
    public long getLabel() {
        return props.label;
    }

    @JsMethod
    public String linkString(PublicKeyHash owner) {
        return props.toLinkString(owner);
    }

    /** What is in the link: its members, or the one path it was recorded under if it has none. */
    @JsMethod
    public String[] paths() {
        if (props.members.isEmpty())
            return new String[]{recordedUnder};
        return props.members.stream().map(m -> m.path).toArray(String[]::new);
    }

    @JsMethod
    public int itemCount() {
        return props.members.isEmpty() ? 1 : props.members.size();
    }

    @JsMethod
    public boolean isWritable() {
        return props.isLinkWritable;
    }

    @JsMethod
    public boolean hasPassword() {
        return ! props.userPassword.isEmpty();
    }

    @JsMethod
    public String expiryString() {
        return props.expiry.map(Object::toString).orElse("");
    }

    @JsMethod
    public String maxRetrievalsString() {
        return props.maxRetrievalsString();
    }

    /** Whether this link already contains the given path, so it is not offered twice. */
    @JsMethod
    public boolean contains(String path) {
        List<String> paths = props.members.isEmpty() ?
                Collections.singletonList(recordedUnder) :
                props.members.stream().map(m -> m.path).collect(Collectors.toList());
        return paths.contains(path);
    }
}
