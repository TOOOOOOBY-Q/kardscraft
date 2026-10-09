package dev.invasion.kardscraft.game;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class AttackLedgerTest {
    private final UUID steve = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test void noCounterBeforeResultAndDuplicateResultCannotCounterAgain() {
        AttackLedger ledger = new AttackLedger();
        AttackLedger.Attack attack = new AttackLedger.Attack(steve, 7, "match-a", true, 100);
        ledger.add(42, attack);
        assertEquals(1, ledger.size());
        assertNull(ledger.resolve(41, "match-a"));
        assertEquals(attack, ledger.resolve(42, "match-a"));
        assertNull(ledger.resolve(42, "match-a"));
        assertEquals(0, ledger.size());
    }

    @Test void oldMatchResultCannotConsumeCurrentAttackWithReusedSequence() {
        AttackLedger ledger = new AttackLedger();
        AttackLedger.Attack attack = new AttackLedger.Attack(steve, 7, "new-match", true, 100);
        ledger.add(1, attack);
        assertNull(ledger.resolve(1, "old-match"));
        assertEquals(attack, ledger.resolve(1, "new-match"));
    }

    @Test void expiredResultAndDisconnectedMatchCannotRetaliate() {
        AttackLedger ledger = new AttackLedger();
        ledger.add(1, new AttackLedger.Attack(steve, 7, "match", true, 100));
        ledger.add(2, new AttackLedger.Attack(steve, 8, "match", true, 200));
        ledger.expire(250, 150);
        assertNull(ledger.resolve(1, "match"));
        assertEquals(1, ledger.size());
        ledger.clear();
        assertNull(ledger.resolve(2, "match"));
    }

    @Test void rejectedSubmissionIsNotRemembered() {
        AttackLedger ledger = new AttackLedger();
        ledger.add(-1, new AttackLedger.Attack(steve, 7, "match", true, 100));
        assertEquals(0, ledger.size());
        assertNull(ledger.resolve(-1, "match"));
    }
}
