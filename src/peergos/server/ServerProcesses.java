package peergos.server;

import peergos.server.storage.*;

public class ServerProcesses {

    public final UserService localApi, p2pApi;
    public final IpfsWrapper ipfs;

    public ServerProcesses(UserService localApi, UserService p2pApi, IpfsWrapper ipfs) {
        this.localApi = localApi;
        this.p2pApi = p2pApi;
        this.ipfs = ipfs;
    }
}
