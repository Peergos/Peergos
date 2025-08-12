package peergos.server.storage;

public interface VersionFilter {

    boolean has(BlockVersion v);

    BlockVersion add(BlockVersion v);
}
