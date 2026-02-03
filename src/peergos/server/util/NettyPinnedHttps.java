package peergos.server.util;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.ssl.*;
import io.netty.resolver.NoopAddressResolverGroup;
import peergos.shared.storage.RateLimitException;
import peergos.shared.util.LRUCache;
import peergos.shared.util.Pair;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class NettyPinnedHttps {

    private static final EventLoopGroup GROUP = new NioEventLoopGroup();

    static LRUCache<String, PinnedHost> dnsCache = new LRUCache<>(10);

    public static PinnedHost getHost(URI original) throws IOException {
        synchronized (dnsCache) {
            String host = original.getHost();
            PinnedHost cached = dnsCache.get(host);
            if (cached != null) {
                cached.ensureRefreshed();
                return cached;
            }
            PinnedHost pinned = new PinnedHost(host);
            dnsCache.put(host, pinned);
            return pinned;
        }
    }

    public static Pair<byte[], String> get(URI uri) throws InterruptedException, IOException {
        if (!"https".equalsIgnoreCase(uri.getScheme()))
            throw new IllegalArgumentException("HTTPS only");

        PinnedHost pinned = getHost(uri);
        InetAddress addr = pinned.next();

        CompletableFuture<Pair<byte[], String>> result = new CompletableFuture<>();

        SslContext sslCtx = SslContextBuilder.forClient().build();

        Bootstrap bootstrap = new Bootstrap()
                .group(GROUP)
                .channel(NioSocketChannel.class)
                .resolver(NoopAddressResolverGroup.INSTANCE)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline p = ch.pipeline();

                        p.addLast(sslCtx.newHandler(
                                ch.alloc(),
                                uri.getHost(),
                                uri.getPort() == -1 ? 443 : uri.getPort()));

                        p.addLast(new HttpClientCodec());
                        p.addLast(new HttpObjectAggregator(2 * 1024 * 1024));

                        p.addLast(new SimpleChannelInboundHandler<FullHttpResponse>() {
                            @Override
                            protected void channelRead0(ChannelHandlerContext ctx,
                                                        FullHttpResponse resp) {
                                int respCode = resp.status().code();
                                if (respCode != 200) {
                                    if (respCode == 502 || respCode == 503)
                                        result.completeExceptionally(new RateLimitException());
                                    else if (respCode == 404) {
                                        result.completeExceptionally(new FileNotFoundException());
                                    } else
                                        result.completeExceptionally(
                                                new RuntimeException("HTTP " + resp.status()));
                                } else {
                                    byte[] body = new byte[resp.content().readableBytes()];
                                    resp.content().readBytes(body);
                                    String version = resp.headers().contains("x-amz-version-id") ?
                                            resp.headers().get("x-amz-version-id") :
                                            null;
                                    result.complete(new Pair<>(body, version));
                                }
                                ctx.close();
                            }

                            @Override
                            public void exceptionCaught(ChannelHandlerContext ctx, Throwable t) {
                                if (t instanceof SslClosedEngineException)
                                    result.completeExceptionally(new RateLimitException());
                                else if (t instanceof SocketException)
                                    result.completeExceptionally(new RateLimitException());
                                else
                                    result.completeExceptionally(t);
                                ctx.close();
                            }
                        });
                    }
                });

        InetSocketAddress remote =
                new InetSocketAddress(addr, uri.getPort() == -1 ? 443 : uri.getPort());

        Channel ch = bootstrap.connect(remote).sync().channel();

        String path = uri.getRawPath()
                + (uri.getRawQuery() != null ? "?" + uri.getRawQuery() : "");

        FullHttpRequest req = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1,
                HttpMethod.GET,
                path);

        req.headers()
                .set(HttpHeaderNames.HOST, uri.getHost())
                .set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);

        ch.writeAndFlush(req).sync();

        return result.join();
    }

    public static Map<String, List<String>> head(URI uri) throws InterruptedException, IOException {
        if (!"https".equalsIgnoreCase(uri.getScheme()))
            throw new IllegalArgumentException("HTTPS only");

        PinnedHost pinned = getHost(uri);
        InetAddress addr = pinned.next();

        CompletableFuture<Map<String, List<String>>> result = new CompletableFuture<>();

        SslContext sslCtx = SslContextBuilder.forClient().build();

        Bootstrap bootstrap = new Bootstrap()
                .group(GROUP)
                .channel(NioSocketChannel.class)
                .resolver(NoopAddressResolverGroup.INSTANCE)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline p = ch.pipeline();

                        p.addLast(sslCtx.newHandler(
                                ch.alloc(),
                                uri.getHost(),
                                uri.getPort() == -1 ? 443 : uri.getPort()));

                        p.addLast(new HttpClientCodec());
                        p.addLast(new HttpObjectAggregator(2 * 1024 * 1024));

                        p.addLast(new SimpleChannelInboundHandler<FullHttpResponse>() {
                            @Override
                            protected void channelRead0(ChannelHandlerContext ctx,
                                                        FullHttpResponse resp) {
                                int respCode = resp.status().code();
                                if (respCode != 200) {
                                    if (respCode == 502 || respCode == 503)
                                        result.completeExceptionally(new RateLimitException());
                                    else if (respCode == 404) {
                                        result.completeExceptionally(new FileNotFoundException());
                                    } else
                                        result.completeExceptionally(
                                                new RuntimeException("HTTP " + resp.status()));
                                } else {
                                    HttpHeaders headers = resp.headers();
                                    Map<String, List<String>> res = new HashMap<>();
                                    headers.forEach(e -> res.put(e.getKey(), List.of(e.getValue())));
                                    result.complete(res);
                                }
                                ctx.close();
                            }

                            @Override
                            public void exceptionCaught(ChannelHandlerContext ctx, Throwable t) {
                                if (t instanceof SslClosedEngineException)
                                    result.completeExceptionally(new RateLimitException());
                                else if (t instanceof SocketException)
                                    result.completeExceptionally(new RateLimitException());
                                else
                                    result.completeExceptionally(t);
                                ctx.close();
                            }
                        });
                    }
                });

        InetSocketAddress remote =
                new InetSocketAddress(addr, uri.getPort() == -1 ? 443 : uri.getPort());

        Channel ch = bootstrap.connect(remote).sync().channel();

        String path = uri.getRawPath()
                + (uri.getRawQuery() != null ? "?" + uri.getRawQuery() : "");

        FullHttpRequest req = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1,
                HttpMethod.HEAD,
                path);

        req.headers()
                .set(HttpHeaderNames.HOST, uri.getHost())
                .set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);

        ch.writeAndFlush(req).sync();

        return result.join();
    }

    public static Pair<byte[], String> putOrPost(String method,
                                                 URI uri,
                                                 Map<String, String> headers,
                                                 byte[] body) throws IOException, InterruptedException {
        if (!"https".equalsIgnoreCase(uri.getScheme()))
            throw new IllegalArgumentException("HTTPS only");

        PinnedHost pinned = getHost(uri);
        InetAddress addr = pinned.next();

        CompletableFuture<Pair<byte[], String>> result = new CompletableFuture<>();

        SslContext sslCtx = SslContextBuilder.forClient().build();

        Bootstrap bootstrap = new Bootstrap()
                .group(GROUP)
                .channel(NioSocketChannel.class)
                .resolver(NoopAddressResolverGroup.INSTANCE)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline p = ch.pipeline();

                        p.addLast(sslCtx.newHandler(
                                ch.alloc(),
                                uri.getHost(),
                                uri.getPort() == -1 ? 443 : uri.getPort()));

                        p.addLast(new HttpClientCodec());
                        p.addLast(new HttpObjectAggregator(2 * 1024 * 1024));

                        p.addLast(new SimpleChannelInboundHandler<FullHttpResponse>() {
                            @Override
                            protected void channelRead0(ChannelHandlerContext ctx,
                                                        FullHttpResponse resp) {
                                int respCode = resp.status().code();
                                if (respCode != 200) {
                                    if (respCode == 500 || respCode == 502 || respCode == 503)
                                        result.completeExceptionally(new RateLimitException());
                                    else if (respCode == 404) {
                                        result.completeExceptionally(new FileNotFoundException());
                                    } else
                                        result.completeExceptionally(
                                                new RuntimeException("HTTP " + resp.status()));
                                } else {
                                    byte[] body = new byte[resp.content().readableBytes()];
                                    resp.content().readBytes(body);
                                    String version = resp.headers().contains("x-amz-version-id") ?
                                            resp.headers().get("x-amz-version-id") :
                                            null;
                                    result.complete(new Pair<>(body, version));
                                }
                                ctx.close();
                            }

                            @Override
                            public void exceptionCaught(ChannelHandlerContext ctx, Throwable t) {
                                if (t instanceof SslClosedEngineException)
                                    result.completeExceptionally(new RateLimitException());
                                else if (t instanceof SocketException)
                                    result.completeExceptionally(new RateLimitException());
                                else
                                    result.completeExceptionally(t);
                                ctx.close();
                            }
                        });
                    }
                });

        InetSocketAddress remote =
                new InetSocketAddress(addr, uri.getPort() == -1 ? 443 : uri.getPort());

        Channel ch = bootstrap.connect(remote).sync().channel();

        String path = uri.getRawPath()
                + (uri.getRawQuery() != null ? "?" + uri.getRawQuery() : "");

        FullHttpRequest req = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1,
                method.equals("PUT") ? HttpMethod.PUT : HttpMethod.POST,
                path,
                Unpooled.wrappedBuffer(body));

        req.headers()
                .set(HttpHeaderNames.HOST, uri.getHost())
                .set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        headers.forEach((k, v) -> req.headers().set(k, v));

        ch.writeAndFlush(req).sync();

        return result.join();
    }
}

