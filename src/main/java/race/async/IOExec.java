package race.async;

import java.util.concurrent.*;

public final class IOExec {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "race-io");
        t.setDaemon(true);
        return t;
    });

    public static CompletableFuture<Void> submit(Runnable r) {
        return CompletableFuture.runAsync(r, IO);
    }
}
