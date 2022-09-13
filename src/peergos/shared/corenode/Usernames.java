package peergos.shared.corenode;

import jsinterop.annotations.*;

public class Usernames {

    @JsProperty
    public static final String REGEX = "^[a-z0-9](?:[a-z0-9]|[-](?=[a-z0-9])){0,31}$";
}
