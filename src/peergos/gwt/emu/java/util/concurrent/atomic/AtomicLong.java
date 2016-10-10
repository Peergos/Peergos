package java.util.concurrent.atomic;

import java.io.Serializable;

public class AtomicLong extends Number implements Serializable {
    private long value;
    public AtomicLong(long value) {
        this.value = value;
    }

    @Override
    public byte byteValue() {
        return (byte) value;
    }

    @Override
    public int intValue() {
        return (int) value;
    }

    @Override
    public long longValue() {
        return value;
    }

    @Override
    public float floatValue() {
        return (float) value;
    }

    @Override
    public double doubleValue() {
        return (double) value;
    }

    public long get()  {
        return longValue();
    }

    public void set(long value) {
        this.value = value;
    }
}
