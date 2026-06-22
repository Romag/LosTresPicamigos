package org.lostrespicamigos;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.lostrespicamigos.agent.AgentRegistry;
import org.lostrespicamigos.config.JsonSupport;
import org.lostrespicamigos.config.PicamigosConfig;
import org.lostrespicamigos.mcp.PicamigosMcpServer;
import org.lostrespicamigos.process.ExecutableResolver;
import org.lostrespicamigos.process.ProcessRunner;
import org.lostrespicamigos.retention.RetentionService;
import org.lostrespicamigos.run.RunService;
import org.lostrespicamigos.run.RunStore;
import org.lostrespicamigos.workspace.GitWorkspaceManager;
import org.lostrespicamigos.workflow.WorkflowService;
import org.lostrespicamigos.workflow.WorkflowStore;

import java.nio.file.Files;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws Exception {
        PicamigosConfig config = PicamigosConfig.fromEnvironment(args);
        Files.createDirectories(config.home());
        ObjectMapper mapper = JsonSupport.createMapper();
        AgentRegistry registry = new AgentRegistry(mapper);
        RunStore store = new RunStore(config.home(), mapper);
        GitWorkspaceManager workspaceManager = new GitWorkspaceManager(config);
        RunService runs = new RunService(config, registry, new ExecutableResolver(), new ProcessRunner(), store,
                workspaceManager);
        WorkflowStore workflowStore = new WorkflowStore(config.home(), mapper);
        WorkflowService workflows = new WorkflowService(runs, workflowStore);
        new RetentionService(config, store, workflowStore, workspaceManager).purgeExpired(java.time.Instant.now());
        PicamigosMcpServer server = new PicamigosMcpServer(config, mapper, runs, workflows);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            workflows.close();
            runs.close();
        }, "picamigos-shutdown"));
    }
}
