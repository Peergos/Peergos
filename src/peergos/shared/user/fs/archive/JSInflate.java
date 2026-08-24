package peergos.shared.user.fs.archive;

import java.util.concurrent.*;

/** Raw deflate decompression in the browser, which the GWT java.util.zip emulation cannot do.
 */
public class JSInflate implements Inflate.Provider {

    @Override
    public Inflate.Session start() {
        return new JSSession(new NativeJSInflate());
    }

    private static class JSSession implements Inflate.Session {
        private final NativeJSInflate js;

        public JSSession(NativeJSInflate js) {
            this.js = js;
        }

        @Override
        public boolean needsInput() {
            return js.needsInput();
        }

        @Override
        public void setInput(byte[] compressed, int offset, int length) {
            js.setInput(compressed, offset, length);
        }

        @Override
        public CompletableFuture<Integer> inflate(byte[] res, int offset, int length) {
            return js.inflate(res, offset, length);
        }

        @Override
        public void finish() {
            js.finish();
        }

        @Override
        public boolean finished() {
            return js.finished();
        }

        @Override
        public void reset() {
            js.reset();
        }

        @Override
        public void close() {
            js.close();
        }
    }
}
