package peergos.shared.corenode;

import jsinterop.annotations.*;

public class Usernames {

    @JsProperty
    public static final String REGEX = "^(?=.{1,32}$)(?![_-])(?!.*[_-]{2})[a-z0-9_-]+(?<![_-])$";
}
