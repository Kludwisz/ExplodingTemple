import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public class Main {
    private static final long BLOCK_SIZE = 1L << 22;

    // args: [seedMin] [seedMax] [maxSpawnDistance] [threads]
    public static void main(String[] args) throws InterruptedException {
        long seedMin = args.length > 0 ? Long.parseLong(args[0]) : 0L;
        long seedMax = args.length > 1 ? Long.parseLong(args[1]) : 1L << 40;
        int maxSpawnDistance = args.length > 2 ? Integer.parseInt(args[2]) : 300;
        int threads = args.length > 3 ? Integer.parseInt(args[3]) : Runtime.getRuntime().availableProcessors();

        System.out.printf("base seeds [%d, %d), temple within %d blocks of spawn, %d threads%n",
                seedMin, seedMax, maxSpawnDistance, threads);

        long t0 = System.nanoTime();
        AtomicLong nextBlock = new AtomicLong(seedMin);
        AtomicLong seedsDone = new AtomicLong(0);

        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Thread worker = new Thread(() -> {
                long start;
                while ((start = nextBlock.getAndAdd(BLOCK_SIZE)) < seedMax) {
                    long end = Math.min(start + BLOCK_SIZE, seedMax);
                    new ExplodingTempleFinder(start, end, maxSpawnDistance).run();
                    seedsDone.addAndGet(end - start);
                }
            });
            worker.start();
            workers.add(worker);
        }

        Thread progress = new Thread(() -> {
            try {
                while (true) {
                    Thread.sleep(60_000);
                    printProgress(t0, seedsDone.get());
                }
            } catch (InterruptedException ignored) {
            }
        });
        progress.setDaemon(true);
        progress.start();

        for (Thread worker : workers) {
            worker.join();
        }
        printProgress(t0, seedsDone.get());
    }

    private static void printProgress(long t0, long seedsDone) {
        double elapsedSecs = (System.nanoTime() - t0) * 1e-9;
        System.out.printf("[progress] %d base seeds in %.0fs (%.2fM/s), %d ravines, %d deadly structure seeds (%d dry), %d world seeds%n",
                seedsDone, elapsedSecs, seedsDone / elapsedSecs / 1e6, ExplodingTempleFinder.ravineCount.get(),
                ExplodingTempleFinder.structureSeedCount.get(), ExplodingTempleFinder.dryStructureSeedCount.get(),
                ExplodingTempleFinder.resultCount.get());
    }
}
