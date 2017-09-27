package java.util.concurrent.atomic;

import java.io.Serializable;

public class AtomicBoolean implements Serializable {
    private boolean value;

    public AtomicBoolean(boolean value) {
        this.value = value;
    }

    public AtomicBoolean() {
        this(false);
    }

    public boolean get()  {
        return value;
    }

    public void set(boolean value) {
        this.value = value;
    }
}
