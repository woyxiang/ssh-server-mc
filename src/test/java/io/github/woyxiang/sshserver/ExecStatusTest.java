package io.github.woyxiang.sshserver;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ExecStatusTest {

    @Test
    void exitCodesAreStableForScripts() {
        assertEquals(0, ExecStatus.COMPLETED.exitCode(false), "completed → 0");
        assertEquals(1, ExecStatus.REJECTED.exitCode(false), "rejected → 1");
        assertEquals(2, ExecStatus.TIMED_OUT.exitCode(false), "timed_out → 2");
        assertEquals(3, ExecStatus.COMPLETION_UNKNOWN.exitCode(false), "completion_unknown → 3");
    }

    @Test
    void truncationUpgradesOnlyCompletedToFour() {
        assertEquals(4, ExecStatus.COMPLETED.exitCode(true), "completed 但截断 → 4");
        assertEquals(1, ExecStatus.REJECTED.exitCode(true), "截断不覆盖 rejected 语义");
        assertEquals(2, ExecStatus.TIMED_OUT.exitCode(true), "截断不覆盖超时语义");
        assertEquals(3, ExecStatus.COMPLETION_UNKNOWN.exitCode(true), "截断不覆盖未知语义");
    }

    @Test
    void wireNamesMatchDocumentedStatuses() {
        assertEquals("completed", ExecStatus.COMPLETED.wire());
        assertEquals("rejected", ExecStatus.REJECTED.wire());
        assertEquals("timed_out", ExecStatus.TIMED_OUT.wire());
        assertEquals("completion_unknown", ExecStatus.COMPLETION_UNKNOWN.wire());
    }
}
