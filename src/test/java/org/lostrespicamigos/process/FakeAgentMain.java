package org.lostrespicamigos.process;

import java.nio.charset.StandardCharsets;

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
            case "flood" -> System.out.print("x".repeat(Integer.parseInt(args[1])));
            case "exit" -> System.exit(Integer.parseInt(args[1]));
            default -> throw new IllegalArgumentException("Unknown mode");
        }
    }
}
