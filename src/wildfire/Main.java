package wildfire;

/**
 * Wildfire Simulation - Sequential Version
 * 
 */
public class Main {

    public static void main(String[] args) {
        // Step 1: Read config from instructions.txt
        SimConfig config = ConfigReader.readConfig("instructions.txt");
        System.out.println("Config loaded: " + config);

        // Step 2: Create the simulation
        WildfireSimulation sim = new WildfireSimulation(config);

        // Step 3: Generate the forest
        sim.generateForest();

        // Step 4: Start the fire
        sim.igniteRandomTiles();

        // Step 5: Open the visualizer window
        SimVisualizer visualizer = new SimVisualizer(sim);

        // Step 6: Run the simulation tick by tick
        sim.run(visualizer);

        System.out.println("Simulation finished!");
        System.out.println("Total ticks: " + sim.getTick());
    }
}
