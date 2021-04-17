package peergos.shared.display;

import jsinterop.annotations.*;
import peergos.shared.cbor.*;
import peergos.shared.social.*;

import java.util.*;

@JsType
public
class Text implements Content, MsgContent {
    public final String content;

    public Text(String content) {
        this.content = content;
    }

    @Override
    public String inlineText() {
        return content;
    }

    @Override
    public Optional<SocialPost.Ref> reference() {
        return Optional.empty();
    }

    @Override
    public CborObject toCbor() {
        return new CborObject.CborString(content);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        peergos.shared.display.Text text = (peergos.shared.display.Text) o;
        return Objects.equals(content, text.content);
    }

    @Override
    public int hashCode() {
        return Objects.hash(content);
    }
}
