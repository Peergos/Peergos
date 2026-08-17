package peergos.server.tests;

import org.junit.Assert;
import org.junit.Test;
import peergos.server.JavaCrypto;
import peergos.server.Main;
import peergos.server.sync.SyncRunner;
import peergos.server.util.Args;
import peergos.shared.Crypto;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

/** The schedule around a pass, with no pairs configured, so no network is involved. */
public class SyncRunnerTests {

    private static final Crypto crypto = JavaCrypto.init();

    private static SyncRunner.ThreadBased runner(Path peergosDir) throws Exception {
        Files.write(peergosDir.resolve("sync-config.json"),
                "{\"pairs\":[],\"maxParallelism\":32,\"minPercentFreeSpace\":5}".getBytes(StandardCharsets.UTF_8));
        Args args = Args.parse(new String[]{
                "-" + Main.PEERGOS_PATH, peergosDir.toString(),
                "-sync-watch-local-dirs", "false"}, Optional.empty(), false);
        return new SyncRunner.ThreadBased(args, null, null, null, crypto);
    }

    private static void awaitPasses(SyncRunner.ThreadBased runner, long target) throws Exception {
        long end = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < end) {
            if (runner.getPassCount() >= target)
                return;
            Thread.sleep(50);
        }
        Assert.fail("Only ran " + runner.getPassCount() + " sync passes, expected " + target);
    }

    @Test
    public void localChangeStartsAPass() throws Exception {
        Path peergosDir = Files.createTempDirectory("peergos-sync-runner");
        Path localDir = Files.createTempDirectory("peergos-sync-runner-local");
        // a pair with an unresolvable link: the pass fails once the watcher has been given
        // the local dir, which is all this needs
        Files.write(peergosDir.resolve("sync-config.json"),
                ("{\"pairs\":[{\"localpath\":\"" + localDir.toString().replace("\\", "\\\\")
                        + "\",\"remotepath\":\"/bob/dir\",\"link\":\"#bogus\",\"syncLocalDeletes\":true,"
                        + "\"syncRemoteDeletes\":true}],\"maxParallelism\":32,\"minPercentFreeSpace\":5}")
                        .getBytes(StandardCharsets.UTF_8));
        Args args = Args.parse(new String[]{
                "-" + Main.PEERGOS_PATH, peergosDir.toString(),
                "-sync-watch-debounce-ms", "500",
                "-sync-watch-min-interval-ms", "100"}, Optional.empty(), false);
        SyncRunner.ThreadBased runner = new SyncRunner.ThreadBased(args, null, null, null, crypto);
        try {
            runner.start();
            awaitPasses(runner, 1);
            Thread.sleep(2_000);
            long afterFirst = runner.getPassCount();

            Files.write(localDir.resolve("file.bin"), "data".getBytes(StandardCharsets.UTF_8));
            awaitPasses(runner, afterFirst + 1);
        } finally {
            runner.close();
        }
    }

    @Test
    public void triggerWhilePausedIsIgnored() throws Exception {
        Path peergosDir = Files.createTempDirectory("peergos-sync-runner");
        SyncRunner.ThreadBased runner = runner(peergosDir);
        try {
            runner.start();
            awaitPasses(runner, 1);
            long afterFirst = runner.getPassCount();

            runner.getStatusHolder().pause();
            runner.runNow(Set.of(peergosDir.toString()));
            Thread.sleep(3_000);
            Assert.assertEquals("a watcher trigger started a pass while paused",
                    afterFirst, runner.getPassCount());
        } finally {
            runner.close();
        }
    }

    @Test
    public void unpausingResumesPromptly() throws Exception {
        Path peergosDir = Files.createTempDirectory("peergos-sync-runner");
        SyncRunner.ThreadBased runner = runner(peergosDir);
        try {
            runner.start();
            awaitPasses(runner, 1);
            runner.getStatusHolder().pause();
            long afterPause = runner.getPassCount();

            // what sync-now does: the 30s schedule must not be waited out
            runner.getStatusHolder().unpause();
            runner.runNow();
            long start = System.currentTimeMillis();
            awaitPasses(runner, afterPause + 1);
            Assert.assertTrue("unpausing took " + (System.currentTimeMillis() - start) + "ms to run a pass",
                    System.currentTimeMillis() - start < 10_000);
        } finally {
            runner.close();
        }
    }
}
