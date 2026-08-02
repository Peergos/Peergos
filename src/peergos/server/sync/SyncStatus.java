package peergos.server.sync;

import java.util.List;

/** The machine readable state of a sync, reported alongside the human readable status message.
 *  Clients (the desktop tray icon, the web ui) should switch on this rather than parsing messages.
 *  (Not to be confused with {@link SyncState}, which is the local file tree db.)
 */
public enum SyncStatus {
    /** No sync pairs are configured, so there is nothing to report. Only ever the global aggregate. */
    NONE,
    SYNCED,
    SYNCING,
    ERROR;

    public static SyncStatus parseOrDefault(String name, SyncStatus fallback) {
        if (name == null)
            return fallback;
        for (SyncStatus s : values()) {
            if (s.name().equals(name))
                return s;
        }
        return fallback;
    }

    /** Combine the per pair states into the single global state a tray icon can display.
     *
     * @param pairStates the state of every configured sync pair
     * @param globalError whether the global status holder is reporting an error
     */
    public static SyncStatus aggregate(List<SyncStatus> pairStates, boolean globalError) {
        if (pairStates.isEmpty())
            return NONE;
        if (globalError || pairStates.contains(ERROR))
            return ERROR;
        if (pairStates.contains(SYNCING))
            return SYNCING;
        return SYNCED;
    }
}
