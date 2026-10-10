import dev.eyuppastirmaci.pecia.testing.LexicalTestCorpus;
import java.nio.file.Files;
import java.nio.file.Path;

/** Writes the shared lexical test corpus into an empty directory, using the current test classes. */
public class WriteCorpus {

    public static void main(String[] args) throws Exception {
        Path root = Files.createDirectories(Path.of(args[0]));
        LexicalTestCorpus.write(root);
    }
}
