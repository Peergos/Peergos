package peergos.server.storage;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import io.ipfs.multihash.Multihash;
import io.libp2p.core.PeerId;
import org.peergos.HttpProxyService;
import org.peergos.net.Handler;
import org.peergos.net.HttpProxyHandler;
import org.peergos.net.ProxyRequest;
import org.peergos.net.ProxyResponse;
import peergos.shared.io.ipfs.cid.Cid;
import peergos.shared.io.ipfs.multibase.Base58;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class PeergosHttpProxyHandler extends HttpProxyHandler {

    public PeergosHttpProxyHandler(HttpProxyService service) {

        super(service);
    }

    @Override
    public void handleCallToAPI(HttpExchange httpExchange) {
    //public void handleCallToAPI(HttpExchange httpExchange) {
        long t1 = System.currentTimeMillis();
        String path = httpExchange.getRequestURI().getPath();
        try {
            if (path.startsWith(HttpProxyService.API_URL)) {
                // /p2p/$target_node_id/http/$target_path
                path = path.substring(HttpProxyService.API_URL.length());
                int streamPathIndex = path.indexOf('/');
                if (streamPathIndex == -1) {
                    throw new IllegalStateException("Expecting p2p request to include path in url");
                }
                String peerId = path.substring(0, streamPathIndex);
                Cid pkiIpfsNodeId = Cid.decodePeerId(peerId);
                Multihash targetNodeId = new Multihash(Multihash.Type.lookup(pkiIpfsNodeId.type.index),
                        pkiIpfsNodeId.getHash());

                String targetPath = path.substring(streamPathIndex);
                if (!targetPath.startsWith(HTTP_REQUEST)) {
                    throw new IllegalStateException("Expecting path to be a http request");
                }
                targetPath = targetPath.substring(HTTP_REQUEST.length() - 1);
                byte[] body = read(httpExchange.getRequestBody());
                Map<String, List<String>> reqQueryParams = org.peergos.util.HttpUtil.parseQuery(httpExchange.getRequestURI().getQuery());

                Map<String, List<String>> reqHeaders = httpExchange.getRequestHeaders().entrySet().stream()
                        .collect(Collectors.toUnmodifiableMap(
                                Map.Entry::getKey, e -> new LinkedList<>(e.getValue())));
                ProxyRequest request = new ProxyRequest(targetPath,
                        ProxyRequest.Method.valueOf(httpExchange.getRequestMethod()),
                        reqHeaders, reqQueryParams, body);
                ProxyResponse response = service.proxyRequest(targetNodeId, request);
                Headers reponseHeaders = httpExchange.getResponseHeaders();
                for (Map.Entry<String, String> entry : response.headers.entrySet()) {
                    reponseHeaders.replace(entry.getKey(), List.of(entry.getValue()));
                }
                httpExchange.sendResponseHeaders(response.statusCode, response.body.length);
                httpExchange.getResponseBody().write(response.body);
            } else {
                throw new IllegalStateException("Unsupported request");
            }
        } catch (Exception e) {
            org.peergos.util.HttpUtil.replyError(httpExchange, e);
        } finally {
            httpExchange.close();
            long t2 = System.currentTimeMillis();
            if (LOGGING)
                LOG.info("API Handler handled " + path + " query in: " + (t2 - t1) + " mS");
        }
    }
}

