package peergos.server.corenode;

import peergos.shared.corenode.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.storage.*;

import java.io.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

public class NonWriteThroughCoreNode implements CoreNode {

    private final CoreNode source;
    private final CoreNode temp;

    public NonWriteThroughCoreNode(CoreNode source, ContentAddressedStorage ipfs) {
        this.source = source;
        try {
            this.temp = UserRepository.buildSqlLite(":memory:", ipfs, 1000);
        } catch (SQLException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @Override
    public CompletableFuture<Optional<PublicKeyHash>> getPublicKeyHash(String username) {
        try {
            Optional<PublicKeyHash> modified = temp.getPublicKeyHash(username).get();
            if (modified.isPresent())
                return CompletableFuture.completedFuture(modified);
            return source.getPublicKeyHash(username);
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @Override
    public CompletableFuture<String> getUsername(PublicKeyHash key) {
        try {
            String modified = temp.getUsername(key).get();
            if (! modified.isEmpty())
                return CompletableFuture.completedFuture(modified);
            return source.getUsername(key);
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @Override
    public CompletableFuture<List<UserPublicKeyLink>> getChain(String username) {
        try {
            List<UserPublicKeyLink> modified = temp.getChain(username).get();
            if (! modified.isEmpty())
                return CompletableFuture.completedFuture(modified);
            return source.getChain(username);
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @Override
    public CompletableFuture<Boolean> updateChain(String username, List<UserPublicKeyLink> chain) {
        try {
            List<UserPublicKeyLink> modified = temp.getChain(username).get();
            if (! modified.isEmpty())
                return temp.updateChain(username, chain);
            List<UserPublicKeyLink> existing = source.getChain(username).get();
            temp.updateChain(username, existing).get();
            return temp.updateChain(username, chain);
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @Override
    public CompletableFuture<List<String>> getUsernames(String prefix) {
        try {
            return CompletableFuture.completedFuture(
                    Stream.concat(
                            source.getUsernames(prefix).get().stream(),
                            temp.getUsernames(prefix).get().stream())
                            .distinct()
                            .collect(Collectors.toList()));
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @Override
    public void close() throws IOException {}
}
