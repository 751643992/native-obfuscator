/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package by.radioegor146;

import by.radioegor146.helpers.ProcessHelper;
import by.radioegor146.helpers.ProcessHelper.ProcessResult;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import org.junit.jupiter.api.function.Executable;

/**
 *
 * @author radioegor146
 */
public class ClassicTest implements Executable {

    private final Path testDirectory;
    private Path tempDirectory;
    
    public ClassicTest(File directory) {
        testDirectory = directory.toPath();
    }
    
    public void clean() {
        try {
            Files.walkFileTree(tempDirectory, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            // ignored
        }
    }
    
    @Override
    public void execute() throws Throwable {
        try {
            Path sourceDirectory = testDirectory.resolve("source");
            if (!sourceDirectory.toFile().exists()) 
                throw new IOException("Source directory not found");

            System.out.println("Preparing...");
            tempDirectory = Files.createTempDirectory("native-obfuscator-test");
            Path tempSourceDirectory = tempDirectory.resolve("source");
            tempSourceDirectory.toFile().mkdirs();
            Path tempClassFilesDirectory = tempDirectory.resolve("classes");
            tempClassFilesDirectory.toFile().mkdirs();
            Path tempOutputDirectory = tempDirectory.resolve("output");
            tempOutputDirectory.toFile().mkdirs();

            for (File sourceFile : sourceDirectory.toFile().listFiles(x -> x.getName().endsWith(".java")))
                Files.copy(sourceFile.toPath(), tempSourceDirectory.resolve(sourceFile.getName()));
            
            System.out.println("Compiling...");
            ProcessHelper.run(tempDirectory, 10000, "javac", "-d", tempClassFilesDirectory.toAbsolutePath().toString(), tempSourceDirectory.toAbsolutePath().toString() + "/*.java")
                    .check("Compilation");
            ProcessHelper.run(tempDirectory, 10000, "jar", "cvfe", tempDirectory.resolve("test.jar").toAbsolutePath().toString(), "Test", "-C", tempClassFilesDirectory.toAbsolutePath().toString() + "/", ".")
                    .check("Jar command");
            
            System.out.println("Processing...");
            new NativeObfuscator().process(tempDirectory.resolve("test.jar"), tempOutputDirectory, new ArrayList<>());
            
            System.out.println("Ideal...");
            ProcessResult idealRunResult = ProcessHelper.run(tempDirectory, 10000, "java", "-jar", tempDirectory.resolve("test.jar").toAbsolutePath().toString());
            idealRunResult.check("Ideal run");
            
            System.out.println("Compiling CPP code...");
            ProcessHelper.run(tempOutputDirectory.resolve("cpp"), 10000, "cmake", ".").check("CMake prepare");
            ProcessHelper.run(tempOutputDirectory.resolve("cpp"), 30000, "cmake", "--build", ".", "--config", "Release").check("CMake build");
            
            System.out.println("Running test...");
            ProcessHelper.run(tempOutputDirectory, 10 * idealRunResult.execTime, "java", "-Djava.library.path=.", tempOutputDirectory.resolve("cpp").resolve("test.jar").toAbsolutePath().toString()).check("CMake prepare");
            
            System.out.println("OK");
        } catch (IOException | RuntimeException e) {
            clean();
            throw e;
        }
        clean();
    }
    
}
