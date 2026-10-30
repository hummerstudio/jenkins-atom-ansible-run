package com.jenkinschina.atom.ansible;

import com.jenkinschina.atom.sdk.AtomContext;
import com.jenkinschina.atom.sdk.AtomResult;
import com.jenkinschina.atom.sdk.AtomTask;
import com.jenkinschina.atom.sdk.ErrorType;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ansible as an atom: run a playbook and read the recap instead of scraping it by eye.
 *
 * <p>The recap line ({@code ok=3 changed=1 unreachable=0 failed=0 skipped=0}) is the only structured output ansible
 * gives by default, so it becomes the atom's outputs — a gate can then be "fail if anything was unreachable".
 */
public class AnsibleRunAtom implements AtomTask {

    private static final Pattern RECAP = Pattern.compile(
            "ok=(\\d+)\\s+changed=(\\d+)\\s+unreachable=(\\d+)\\s+failed=(\\d+)\\s+skipped=(\\d+)"
                    + "(?:\\s+rescued=(\\d+)\\s+ignored=(\\d+))?");

    @Override
    public AtomResult run(AtomContext context) throws Exception {
        Path workspace = context.getWorkspace();
        String playbook = context.getParam("playbook", "site.yml");
        String inventory = context.getParam("inventory", "");
        String limit = context.getParam("limit", "");
        String tags = context.getParam("tags", "");
        String skipTags = context.getParam("skipTags", "");
        String extraVars = context.getParam("extraVars", "");
        String extraArgs = context.getParam("extraArgs", "");
        boolean check = context.getBooleanParam("check", false);
        int forks = context.getIntParam("forks", 0);

        Path play = workspace.resolve(playbook).normalize();
        if (!play.startsWith(workspace) || !Files.isRegularFile(play)) {
            return AtomResult.error(ErrorType.USER, 40001, "no playbook at " + playbook + " in the workspace");
        }
        String ansible = resolve("ansible-playbook");
        if (ansible == null) {
            return AtomResult.error(ErrorType.ENVIRONMENT, 40002,
                    "ansible-playbook is not on the PATH of this agent; install ansible or point the job at a container");
        }

        List<String> command = new ArrayList<>(List.of(ansible, play.toString()));
        if (!inventory.isBlank()) {
            command.add("-i");
            command.add(inventory);
        }
        if (!limit.isBlank()) {
            command.add("--limit");
            command.add(limit);
        }
        if (!tags.isBlank()) {
            command.add("--tags");
            command.add(tags);
        }
        if (!skipTags.isBlank()) {
            command.add("--skip-tags");
            command.add(skipTags);
        }
        if (!extraVars.isBlank()) {
            command.add("-e");
            command.add(extraVars);
        }
        if (check) {
            command.add("--check");
        }
        if (forks > 0) {
            command.add("-f");
            command.add(String.valueOf(forks));
        }
        for (String arg : extraArgs.split("\\s+")) {
            if (!arg.isBlank()) {
                command.add(arg);
            }
        }

        context.log("[ansible] " + String.join(" ", command));
        ProcessBuilder builder = new ProcessBuilder(command).directory(workspace.toFile()).redirectErrorStream(true);
        builder.environment().put("ANSIBLE_FORCE_COLOR", "0");
        Process process = builder.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                context.log("[ansible] " + line);
                output.append(line).append('\n');
                if (output.length() > 200_000) {
                    output.delete(0, 100_000);
                }
            }
        }
        int exit = process.waitFor();

        Matcher recap = RECAP.matcher(output.toString());
        int ok = 0;
        int changed = 0;
        int unreachable = 0;
        int failed = 0;
        int skipped = 0;
        if (recap.find()) {
            ok = Integer.parseInt(recap.group(1));
            changed = Integer.parseInt(recap.group(2));
            unreachable = Integer.parseInt(recap.group(3));
            failed = Integer.parseInt(recap.group(4));
            skipped = Integer.parseInt(recap.group(5));
        }

        AtomResult result = AtomResult.success()
                .data("ok", String.valueOf(ok))
                .data("changed", String.valueOf(changed))
                .data("unreachable", String.valueOf(unreachable))
                .data("failed", String.valueOf(failed))
                .data("skipped", String.valueOf(skipped));

        if (unreachable > 0 && context.getBooleanParam("failOnUnreachable", true)) {
            return AtomResult.failure(unreachable + " host(s) unreachable");
        }
        if (exit != 0 || failed > 0) {
            return AtomResult.error(ErrorType.THIRD_PART, 40003,
                    "ansible-playbook exited " + exit + " with " + failed + " failed task(s)");
        }
        return result;
    }

    private static String resolve(String name) {
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        for (String entry : path.split(java.io.File.pathSeparator)) {
            if (entry.isBlank()) {
                continue;
            }
            Path candidate = Path.of(entry).resolve(name);
            if (Files.isExecutable(candidate)) {
                return candidate.toAbsolutePath().toString();
            }
        }
        return null;
    }
}
