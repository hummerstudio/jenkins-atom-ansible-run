package atom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jenkinschina.atom.sdk.testing.Atom;
import com.jenkinschina.atom.sdk.testing.AtomResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The ansible atom, started the way the runner starts it, with a fake {@code ansible-playbook} on the PATH.
 *
 * <p>These are the checks {@code tools/smoke.sh} made: the recap becomes the outputs, {@code --limit}/{@code --tags}
 * reach the command line, an unreachable host fails the step, and a missing ansible is an environment error.
 */
class AnsibleRunIT {

    private static final String RECAP = "#!/bin/sh\n"
            + "printf '%s\\n' \"$*\" >> \"$SMOKE_ARGV\"\n"
            + "if [ -n \"${SMOKE_UNREACHABLE:-}\" ]; then\n"
            + "  echo \"host1 : ok=0 changed=0 unreachable=1 failed=0 skipped=0\"\n"
            + "else\n"
            + "  echo \"host1 : ok=3 changed=1 unreachable=0 failed=0 skipped=2\"\n"
            + "fi\n"
            + "exit 0\n";

    private Atom atom;

    @BeforeEach
    void setUp() throws Exception {
        atom = Atom.of();
    }

    @Test
    @DisplayName("the recap line becomes the outputs, and --limit/--tags reach ansible")
    void parsesTheRecap() throws Exception {
        AtomResult run = atom.file("site.yml", "PLAY")
                .input("{\"playbook\": \"site.yml\", \"inventory\": \"hosts\", \"limit\": \"web\", \"tags\": \"deploy\"}")
                .bin("ansible-playbook", RECAP)
                .env("SMOKE_ARGV", "argv.txt")
                .run();

        run.assertOk();
        assertEquals("3", run.field("ok"));
        assertEquals("1", run.field("changed"));
        assertEquals("0", run.field("unreachable"));
        assertEquals("0", run.field("failed"));
        assertEquals("2", run.field("skipped"));

        String argv = run.file("argv.txt");
        assertTrue(argv.contains("--limit web"), "the limit must be passed:\n" + argv);
        assertTrue(argv.contains("--tags deploy"), "the tags must be passed:\n" + argv);
    }

    @Test
    @DisplayName("an unreachable host fails the step")
    void failsOnUnreachable() throws Exception {
        AtomResult run = atom.file("site.yml", "PLAY")
                .input("{\"playbook\": \"site.yml\"}")
                .bin("ansible-playbook", RECAP)
                .env("SMOKE_ARGV", "argv.txt")
                .env("SMOKE_UNREACHABLE", "1")
                .run();

        run.assertRefused();
        assertTrue(run.console().contains("unreachable") || run.field("message").contains("unreachable"),
                "the failure must say a host was unreachable:\n" + run.console());
    }

    @Test
    @DisplayName("ansible not on the PATH is an environment error, not a build failure")
    void failsWhenAnsibleIsMissing() throws Exception {
        AtomResult run = atom.file("site.yml", "PLAY")
                .input("{\"playbook\": \"site.yml\"}")
                .env("PATH", "/nonexistent-for-the-ansible-test")
                .run();

        run.assertRefused();
        assertEquals("5", run.field("errorType"), "ENVIRONMENT(5):\n" + run.console());
        assertTrue(run.field("message").contains("ansible-playbook"), run.field("message"));
    }
}
