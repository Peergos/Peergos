package peergos.shared.user.fs.erasure;

public abstract class GaloisField
{
    public abstract int size();

    public abstract int mask();

    public abstract int exp(int y);

    public abstract int mul(int x, int y);

    public abstract int div(int x, int y);
}
