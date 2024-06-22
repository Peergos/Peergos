package peergos.server.storage;

import peergos.shared.storage.*;

import java.time.*;
import java.util.*;

public interface LinkRetrievalCounter {

    void increment(String owner, long label);

    long getCount(String owner, long label);

    Optional<LocalDateTime> getLatestModificationTime(String owner);

    void setCounts(String owner, LinkCounts counts);

    LinkCounts getUpdatedCounts(String owner, LocalDateTime after);

}
