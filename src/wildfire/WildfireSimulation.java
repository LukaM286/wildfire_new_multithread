package wildfire;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;

/**
 * 
 *
 *   
 *   - Grid razdeli na pasove (en pas na thread)
 *   - CyclicBarrier poskrbi da vsi threadi končajo fazo 1
 *     preden katerikoli začne fazo 2
 */
public class WildfireSimulation {

    private final SimConfig config;
    private final Random    rng;

    private TileState[][] grid;
    private int[][]       burnTimer;
    private int           tick;

    // shouldIgnite[r][c] = true če ta tile dobi ogenj v tem ticku
    // (shared med threadi, ampak vsak thread piše samo v svoj pas)
    private boolean[][] shouldIgnite;

    private final int numThreads;

    public WildfireSimulation(SimConfig config) {
        this.config     = config;
        this.rng        = new Random(config.seed);
        this.grid       = new TileState[config.N][config.M];
        this.burnTimer  = new int[config.N][config.M];
        this.shouldIgnite = new boolean[config.N][config.M];
        this.tick       = 0;

        // Uporabi toliko threadov kot ima računalnik jeder
        this.numThreads = Runtime.getRuntime().availableProcessors();
        System.out.println("Threads: " + numThreads);

        for (int r = 0; r < config.N; r++)
            for (int c = 0; c < config.M; c++)
                grid[r][c] = TileState.GRASS;
    }

    // 
    // generateForest() in igniteRandomTiles() sta enaka kot v sekvenčni, preveri če dela
    // 

    public void generateForest() {
        int totalTiles   = config.N * config.M;
        int targetForest = totalTiles / 2;

        int row = rng.nextInt(config.N);
        int col = rng.nextInt(config.M);
        int forestCount = 0;

        int[] dRow = {-1, 1, 0, 0};
        int[] dCol = { 0, 0,-1, 1};

        while (forestCount < targetForest) {
            if (grid[row][col] == TileState.GRASS) {
                grid[row][col] = TileState.FOREST;
                forestCount++;
            }
            int dir = rng.nextInt(4);
            int newRow = row + dRow[dir];
            int newCol = col + dCol[dir];
            if (newRow >= 0 && newRow < config.N && newCol >= 0 && newCol < config.M) {
                row = newRow;
                col = newCol;
            }
        }
        System.out.printf("Forest generated: %d tiles (%.1f%%)%n",
            forestCount, 100.0 * forestCount / totalTiles);
    }

    public void igniteRandomTiles() {
        List<int[]> forestTiles = new ArrayList<>();
        for (int r = 0; r < config.N; r++)
            for (int c = 0; c < config.M; c++)
                if (grid[r][c] == TileState.FOREST)
                    forestTiles.add(new int[]{r, c});

        int ignitions = Math.min(config.K, forestTiles.size());
        Collections.shuffle(forestTiles, rng);
        for (int i = 0; i < ignitions; i++) {
            int r = forestTiles.get(i)[0];
            int c = forestTiles.get(i)[1];
            grid[r][c]      = TileState.BURNING;
            burnTimer[r][c] = 1;
        }
        System.out.printf("Fire started at %d tiles%n", ignitions);
    }

    // 
    // Glavna zanka, zdaj s threadi
    // 

    public void run(SimVisualizer visualizer) {

        // Barrier za numThreads DELAVCEV + 1 GLAVNI thread
        CyclicBarrier barrier = new CyclicBarrier(numThreads + 1);

        Thread[] threads = new Thread[numThreads];
        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            threads[t] = new Thread(() -> workerLoop(threadId, barrier));
            threads[t].start();
        }

        while (hasBurningTile()) {
            try {
                // Čakaj da vsi threadi končajo FAZO 1
                barrier.await();
                // Čakaj da vsi threadi končajo FAZO 2
                barrier.await();
            } catch (Exception e) { break; }

            tick++;

            if (visualizer != null) {
                visualizer.repaintAndWait();
            }
        }

        for (Thread t : threads) t.interrupt();
    }

    /**
     * Vsak delavski thread teče v tej zanki dokler ga ne prekine.
     *
     * @param threadId  kateri thread je ta (0, 1, 2, ...)
     * @param barrier   skupni barrier za sinhronizacijo
     */
    private void workerLoop(int threadId, CyclicBarrier barrier) {

        // Izračunaj kateri pas vrstic pripada temu threadu
        // Npr. thread 0 od 4 na gridu 100 vrstic → vrstice 0-24
        int rowsPerThread = config.N / numThreads;
        int rowStart = threadId * rowsPerThread;
        int rowEnd   = (threadId == numThreads - 1) //rowend = config.N
                       ? config.N                    // zadnji thread vzame preostanek
                       : rowStart + rowsPerThread;

        // Vsak thread ima svoj Random da ni race conditiona na rng
        Random localRng = new Random(config.seed + threadId);

        while (!Thread.currentThread().isInterrupted()) { // dokler main thread ne pošlje signala
            try {
                // --- FAZA 1: izračunaj shouldIgnite za svoj pas ---
                for (int r = rowStart; r < rowEnd; r++) {
                    for (int c = 0; c < config.M; c++) {
                        if (grid[r][c] == TileState.BURNING) {
                            for (int dr = -1; dr <= 1; dr++) {
                                for (int dc = -1; dc <= 1; dc++) {
                                    if (dr == 0 && dc == 0) continue;
                                    int nr = r + dr;
                                    int nc = c + dc;
                                    if (nr < 0 || nr >= config.N || nc < 0 || nc >= config.M) continue;
                                    if (grid[nr][nc] == TileState.FOREST) {
                                        if (localRng.nextDouble() < config.pSpread) {
                                            shouldIgnite[nr][nc] = true;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Počakaj da VSI threadi končajo fazo 1
                barrier.await();

                // --- FAZA 2: apliciraj spremembe za svoj pas ---
                for (int r = rowStart; r < rowEnd; r++) {
                    for (int c = 0; c < config.M; c++) {
                        if (shouldIgnite[r][c]) {
                            grid[r][c]        = TileState.BURNING;
                            burnTimer[r][c]   = 1;
                            shouldIgnite[r][c] = false; // resetiraj za naslednji tick, ročno, skupno vsem threadom

                        } else if (grid[r][c] == TileState.BURNING) {
                            burnTimer[r][c]++;
                            if (burnTimer[r][c] > config.burnTicks) {
                                grid[r][c]      = TileState.BURNED;
                                burnTimer[r][c] = 0;
                            }
                        }
                    }
                }

                // Počakaj da VSI threadi končajo fazo 2 preden začne naslednji tick
                barrier.await();

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (BrokenBarrierException e) {
                break; // glavni thread prekine simulacijo
            }
        }
    }

    // 
    // Pomožne metode
    // 

    private boolean hasBurningTile() {
        for (int r = 0; r < config.N; r++)
            for (int c = 0; c < config.M; c++)
                if (grid[r][c] == TileState.BURNING) return true;
        return false;
    }

    public TileState[][] getGrid()   { return grid; }
    public SimConfig     getConfig() { return config; }
    public int           getTick()   { return tick; }
}