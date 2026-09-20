public class Main {
    public static void main(String[] args) {
        long t0 = System.nanoTime();
        new ExplodingTempleFinder(20_000_000L, 100_000_000L, 100).run();
        double elapsedSecs = (System.nanoTime() - t0) * 1e-9;
        System.out.println(ExplodingTempleFinder.resultCount.get() / elapsedSecs);
    }
}
