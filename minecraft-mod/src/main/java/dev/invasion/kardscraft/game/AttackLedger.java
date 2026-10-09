package dev.invasion.kardscraft.game;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Server-thread bookkeeping: a submitted hit never causes retaliation before an explicit result. */
public final class AttackLedger {
    public record Attack(UUID attacker, int cardId, String matchId, boolean melee, long submittedAt) {}
    private final Map<Integer, Attack> pending = new HashMap<>();

    public void add(int sequence, Attack attack) {
        if (sequence >= 0) pending.put(sequence, attack);
    }

    /** Results from another match must not consume a current request with the same sequence. */
    public Attack resolve(int sequence, String matchId) {
        Attack attack = pending.get(sequence);
        if (attack == null || !attack.matchId().equals(matchId)) return null;
        return pending.remove(sequence);
    }

    public void expire(long now, long timeout) {
        pending.values().removeIf(attack -> now - attack.submittedAt() >= timeout);
    }

    public void clear() { pending.clear(); }
    public int size() { return pending.size(); }
}
