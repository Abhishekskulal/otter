package com.otter.server.session;

import com.otter.ot.OperationSequence;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Append-only history of committed operations for one document. All access happens on the owning
 * {@link DocumentSession}'s serialized command loop, so no internal synchronization is needed.
 *
 * <p>The log holds revisions {@code (baseRevision, currentRevision]}. {@code baseRevision} is 0 for a
 * document built from revision 1, but after a reload it is the revision of the snapshot the state was
 * rebuilt from — everything at or below it has been compacted away and lives only in that snapshot.
 */
public final class RevisionLog {

    public record Entry(long revision, OperationSequence ops, UUID clientOpId, UUID authorId) {
    }

    private final List<Entry> entries = new ArrayList<>();
    private final Map<UUID, Entry> byClientOpId = new HashMap<>();
    private long baseRevision = 0;
    private long currentRevision = 0;

    /**
     * Replaces the log with history rebuilt from storage. {@code loadedBaseRevision} is the snapshot
     * revision the fold started from; {@code loaded} are the contiguous operations after it.
     */
    public void seed(long loadedBaseRevision, List<Entry> loaded) {
        entries.clear();
        byClientOpId.clear();
        baseRevision = loadedBaseRevision;
        currentRevision = loadedBaseRevision;
        for (Entry entry : loaded) {
            entries.add(entry);
            byClientOpId.put(entry.clientOpId(), entry);
            currentRevision = entry.revision();
        }
    }

    public long currentRevision() {
        return currentRevision;
    }

    /** The oldest revision an incoming operation may still be parented at; below it, history is gone. */
    public long oldestRetainedRevision() {
        return baseRevision;
    }

    public Entry append(OperationSequence ops, UUID clientOpId, UUID authorId) {
        Entry entry = new Entry(++currentRevision, ops, clientOpId, authorId);
        entries.add(entry);
        byClientOpId.put(clientOpId, entry);
        return entry;
    }

    public Entry findByClientOpId(UUID clientOpId) {
        return byClientOpId.get(clientOpId);
    }

    /**
     * Operations committed after {@code fromRevision}, in commit order — the history an incoming
     * operation parented at {@code fromRevision} is concurrent with. The caller guarantees
     * {@code fromRevision >= oldestRetainedRevision()}.
     */
    public List<OperationSequence> since(long fromRevision) {
        if (fromRevision >= currentRevision) {
            return Collections.emptyList();
        }
        int start = (int) (fromRevision - baseRevision);
        List<OperationSequence> result = new ArrayList<>();
        for (int i = start; i < entries.size(); i++) {
            result.add(entries.get(i).ops());
        }
        return result;
    }

    /** The full entries committed after {@code fromRevision} — the reconnect replay feed. */
    public List<Entry> entriesSince(long fromRevision) {
        if (fromRevision >= currentRevision) {
            return Collections.emptyList();
        }
        int start = (int) (fromRevision - baseRevision);
        return new ArrayList<>(entries.subList(start, entries.size()));
    }
}
