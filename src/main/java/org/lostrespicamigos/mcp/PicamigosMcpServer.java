package org.lostrespicamigos.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import org.lostrespicamigos.config.PicamigosConfig;
import org.lostrespicamigos.run.RunService;
import org.lostrespicamigos.workflow.WorkflowService;

public final class PicamigosMcpServer implements AutoCloseable {
    private final McpSyncServer server;

    public PicamigosMcpServer(PicamigosConfig config, ObjectMapper mapper, RunService runs, WorkflowService workflows) {
        ObjectMapper protocolMapper = mapper.copy().disable(SerializationFeature.INDENT_OUTPUT);
        var transport = new StdioServerTransportProvider(new JacksonMcpJsonMapper(protocolMapper));
        var tools = new McpTools(runs, workflows, config, mapper);
        var resource = new RunResource(runs);
        this.server = McpServer.sync(transport)
                .serverInfo("los-tres-picamigos", "0.1.0")
                .instructions("Delegate only to adjacent local coding agents. Inspect results; do not treat them as automatically correct.")
                .strictToolNameValidation(true)
                .validateToolInputs(true)
                .capabilities(ServerCapabilities.builder().tools(false).resources(false, false).build())
                .tools(tools.all())
                .resourceTemplates(resource.specification())
                .build();
    }

    @Override
    public void close() {
        server.closeGracefully();
    }
}
