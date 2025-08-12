package peergos.server.storage;

import org.peergos.blockstore.filters.ChainedInfiniFilter;
import org.peergos.util.Logging;

import java.util.List;
import java.util.logging.Logger;
import peergos.shared.cbor.CborObject;

public class CidVersionInfiniFilter implements VersionFilter {

    private static final Logger LOG = Logging.LOG();

    private final ChainedInfiniFilter filter;

    private CidVersionInfiniFilter(ChainedInfiniFilter filter) {
        this.filter = filter;
    }

    private byte[] toBytes(BlockVersion v) {
        return new CborObject.CborList(List.of(
                new CborObject.CborByteArray(v.cid.toBytes()),
                new CborObject.CborString(v.version)
        )).serialize();
    }

    @Override
    public boolean has(BlockVersion v) {
        return filter.search(toBytes(v));
    }

    @Override
    public BlockVersion add(BlockVersion v) {
        filter.insert(toBytes(v), true);
        return v;
    }

    public static CidVersionInfiniFilter build(long nBlocks, double falsePositiveRate) {
        int nextPowerOfTwo = Math.max(17, (int) (1 + Math.log(nBlocks) / Math.log(2)));
        double expansionAlpha = 0.8;
        int bitsPerEntry = (int)(4 - Math.log(falsePositiveRate / expansionAlpha) / Math.log(2) + 1);
        LOG.info("Using infini filter of initial size " + ((double)(bitsPerEntry * (1 << nextPowerOfTwo) / 8) / 1024 / 1024) + " MiB");
        ChainedInfiniFilter infini = new ChainedInfiniFilter(nextPowerOfTwo, bitsPerEntry);
        infini.set_expand_autonomously(true);
        return new CidVersionInfiniFilter(infini);
    }
}

