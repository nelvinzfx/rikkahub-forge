package me.rerere.rikkahub.data.ai

import me.rerere.rikkahub.data.model.OrchestratorMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrchestratorPolicyTest {
    @Test fun offOmitsPreamble() {
        assertEquals("", buildOrchestratorPreamble(OrchestratorMode.OFF, false))
    }

    @Test fun autoIncludesDecisionPolicy() {
        val prompt = buildOrchestratorPreamble(OrchestratorMode.AUTO, false)
        assertTrue(prompt.contains("DECIDE FIRST"))
        assertFalse(prompt.contains("FORCE THIS TURN"))
    }

    @Test fun forceRequiresMeaningfulDelegation() {
        val prompt = buildOrchestratorPreamble(OrchestratorMode.FORCE, false)
        assertTrue(prompt.contains("FORCE THIS TURN"))
        assertTrue(prompt.contains("meaningful"))
    }

    @Test fun workersNeverReceiveOrchestratorPreamble() {
        assertEquals("", buildOrchestratorPreamble(OrchestratorMode.FORCE, true))
    }
}
