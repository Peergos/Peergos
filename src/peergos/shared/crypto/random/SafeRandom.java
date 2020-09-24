package peergos.shared.crypto.random;

import jsinterop.annotations.JsType;
import peergos.shared.crypto.*;

@JsType
public interface SafeRandom {

    void randombytes(byte[] b, int offset, int len);

    default byte[] randomBytes(int len) {
        byte[] res = new byte[len];
        randombytes(res, 0, len);
        return res;
    }

    class Javascript implements SafeRandom {
        JSNaCl scriptJS = new JSNaCl();

        @Override
        public void randombytes(byte[] b, int offset, int len) {
            byte[] r = scriptJS.randombytes(len);
            System.arraycopy(r, 0, b, offset, len);
        }
    }
}
