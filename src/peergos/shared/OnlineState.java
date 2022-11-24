package peergos.shared;

public class OnlineState {

private final NativeJsOnlineState online;

    public OnlineState(NativeJsOnlineState online) {
        this.online = online;
    }

    public boolean isOnline() {
        return online.isOnline();
    }
}
