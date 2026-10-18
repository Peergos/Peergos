package peergos.shared.routing;

import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.*;

import java.util.*;

/** An identity's or a server's records, whichever the subject is. */
public interface SubjectLog {

    Subject subject();

    RecordLog.Change<? extends SubjectLog> add(Verified<? extends SignedRecord> v);

    /** Every record kept, in a canonical order. */
    List<Verified<? extends SignedRecord>> held();

    Optional<? extends Verified<? extends SignedRecord>> inception();

    /** Whether there is anything to resolve: an inception, or a legacy base. */
    boolean exists();

    /** The same inception with a different admission or proof of work, which sit outside its hash. */
    SubjectLog withInception(Verified<? extends SignedRecord> v);

    static SubjectLog empty(Subject s) {
        return s.isIdentity() ?
                IdentityLog.empty((PublicKeyHash) s.id) :
                ServerLog.empty(s.id);
    }

    /** Offers every record, then the unconnected ones again until nothing changes. */
    static SubjectLog build(SubjectLog base, List<Verified<? extends SignedRecord>> records) {
        SubjectLog log = base;
        List<Verified<? extends SignedRecord>> pending = new ArrayList<>(records);
        boolean progress = true;
        while (progress && ! pending.isEmpty()) {
            progress = false;
            List<Verified<? extends SignedRecord>> retry = new ArrayList<>();
            for (Verified<? extends SignedRecord> v : pending) {
                RecordLog.Change<? extends SubjectLog> c = log.add(v);
                if (c.outcome == Outcome.Unconnected)
                    retry.add(v);
                else
                    progress = true;
                log = c.log;
            }
            pending = retry;
        }
        return log;
    }
}
