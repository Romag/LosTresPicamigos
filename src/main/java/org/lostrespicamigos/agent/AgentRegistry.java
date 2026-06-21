package org.lostrespicamigos.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.lostrespicamigos.domain.AgentId;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class AgentRegistry {
    private final Map<AgentId, AgentAdapter> adapters;

    public AgentRegistry(ObjectMapper mapper) {
        EnumMap<AgentId, AgentAdapter> values = new EnumMap<>(AgentId.class);
        List<AgentAdapter> all = List.of(new CodexAdapter(mapper), new ClaudeAdapter(mapper), new AntigravityAdapter());
        all.forEach(adapter -> values.put(adapter.id(), adapter));
        this.adapters = Map.copyOf(values);
    }

    public AgentAdapter get(AgentId id) {
        AgentAdapter adapter = adapters.get(id);
        if (adapter == null) throw new IllegalArgumentException("No adapter for " + id);
        return adapter;
    }

    public List<AgentAdapter> all() {
        return adapters.values().stream().toList();
    }
}
