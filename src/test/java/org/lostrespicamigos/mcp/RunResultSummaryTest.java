package org.lostrespicamigos.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.lostrespicamigos.config.JsonSupport;
import org.lostrespicamigos.domain.AgentResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunResultSummaryTest {
    @Test
    void excludesRawStreamsAndBoundsInlineText() {
        AgentResult result = new AgentResult("x".repeat(RunResultSummary.MAX_TEXT_CHARACTERS + 1), "session",
                List.of("warning"), "raw-stdout-sentinel", "raw-stderr-sentinel", 0);

        JsonNode json = JsonSupport.createMapper().valueToTree(RunResultSummary.from(result));

        assertEquals(RunResultSummary.MAX_TEXT_CHARACTERS, json.path("text").asText().length());
        assertTrue(json.path("textTruncated").asBoolean());
        assertEquals("session", json.path("sessionId").asText());
        assertEquals(0, json.path("exitCode").asInt());
        assertFalse(json.has("rawStdout"));
        assertFalse(json.has("rawStderr"));
        assertFalse(json.toString().contains("raw-stdout-sentinel"));
        assertFalse(json.toString().contains("raw-stderr-sentinel"));
    }
}
