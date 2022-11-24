package peergos.shared;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

public class OnlineState {

    private final NativeJsOnlineState online;
    private final AtomicBoolean testedState = new AtomicBoolean(true);
    private final Supplier<CompletableFuture<Boolean>> updater;
    private long lastUpdate = 0;

    public OnlineState(NativeJsOnlineState online, Supplier<CompletableFuture<Boolean>> updater) {
        this.online = online;
        this.updater = updater;
    }

    public boolean isOnline() {
        return online.isOnline() && testedState.get();
    }

    public synchronized void update() {
        if (! testedState.get()) {
            long now = System.currentTimeMillis();
            if (now > lastUpdate + 10_000) {
                lastUpdate = now;
                updater.get().thenAccept(testedState::set);
            }
        }
    }

    public void updateAsync() {
        ForkJoinPool.commonPool().submit(this::update);
    }

    public void handleRequestException(Throwable t) {
        if (t.toString().contains("ConnectException"))
            testedState.set(false);
    }
}
