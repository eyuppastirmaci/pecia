import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader;
import dev.eyuppastirmaci.pecia.config.PeciaConfigParser;
import dev.eyuppastirmaci.pecia.index.IndexService;
import java.nio.file.Path;

/**
 * Writes an index with the IndexService of whichever core JAR is on the classpath: commit e180141 writes schema V2,
 * commit 5b55a5a writes V3, and the current build writes V4.
 * Usage: java -cp <core JAR>:<its runtime JARs> GenerateIndex.java <project directory>
 */
public class GenerateIndex {

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toRealPath();
        System.out.println(new IndexService(new PeciaConfigLoader(new PeciaConfigParser())).index(root));
    }
}
