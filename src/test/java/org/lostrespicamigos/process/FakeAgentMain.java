package org.lostrespicamigos.process;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class FakeAgentMain {
    private FakeAgentMain() {
    }

    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "echo" -> {
                String prompt = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
                System.err.print("progress");
                System.out.print("OUT:" + prompt);
            }
            case "sleep" -> Thread.sleep(Long.parseLong(args[1]));
            case "spawn-child" -> {
                long sleepMillis = Long.parseLong(args[1]);
                Process child = new ProcessBuilder(fakeCommand("sleep", Long.toString(sleepMillis)))
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
                child.getOutputStream().close();
                System.out.println("CHILD:" + child.pid());
                System.out.flush();
                Thread.sleep(sleepMillis);
            }
            case "flood" -> System.out.print("x".repeat(Integer.parseInt(args[1])));
            case "exit" -> System.exit(Integer.parseInt(args[1]));
            default -> throw new IllegalArgumentException("Unknown mode");
        }
    }

    private static List<String> fakeCommand(String... fakeArguments) {
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        Path java = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java");
        List<String> command = new ArrayList<>(List.of(java.toString(), "-cp",
                System.getProperty("java.class.path"), FakeAgentMain.class.getName()));
        command.addAll(List.of(fakeArguments));
        return command;
    }
}
