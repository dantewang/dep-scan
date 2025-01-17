import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

public class JavaxChecker {

	public static void scan(Path csvPath) throws IOException {
		List<String> lines = Files.readAllLines(csvPath);

		Set<String> checkedDependencies = new LinkedHashSet<>();

		for (String line : lines) {
			String[] dependencyColumns = line.split(",");

			if (dependencyColumns.length != 5) {
				continue;
			}

			String dependency = dependencyColumns[0] + ":" + dependencyColumns[1] + ":" + dependencyColumns[2];

			if (!checkedDependencies.add(dependency)) {
				continue;
			}

			Path jarPath = _downloadDependency(dependencyColumns, null);

			List<String> javaxImports = _checkJar(jarPath);

			if (javaxImports == null) {
				Path sourceJarPath = _downloadDependency(dependencyColumns, "sources");

				javaxImports = _checkSourceJar(sourceJarPath);
			}

			if (javaxImports.isEmpty()) {
				continue;
			}

			System.out.println(dependency);

			javaxImports.forEach(javaImport -> System.out.println("\t" + javaImport));
		}
	}

	private static Path _downloadDependency(String[] columns, String sources) {
		return null;
	}

	/**
	 * @param jarPath The path object to a jar file.
	 * @return 	null, if the jar does not have the "Import-Package" header;
	 * 			list of javax imports, otherwise.
	 * @throws IOException When an IOException occurs.
	 */
	private static List<String> _checkJar(Path jarPath) throws IOException {
		try (JarFile jarFile = new JarFile(jarPath.toFile())) {
			Manifest manifest = jarFile.getManifest();

			Attributes attributes = manifest.getMainAttributes();

			String importPackageAttribute = attributes.getValue("Import-Package");

			if (importPackageAttribute == null) {
				return null;
			}

			List<String> javaxImportPackages = new ArrayList<>();

			String[] importPackages = importPackageAttribute.split(",");

			for (String importPackage : importPackages) {
				if (importPackage.startsWith("javax")) {
					javaxImportPackages.add(importPackage);
				}
			}

			return javaxImportPackages;
		}
	}

	private static List<String> _checkSourceJar(Path sourceJarPath) throws IOException {
		try (FileSystem fileSystem = FileSystems.newFileSystem(sourceJarPath)) {

			// Zip file has only 1 root dir

			Path rootPath = fileSystem.getRootDirectories().iterator().next();

			List<String> javaxImports = new ArrayList<>();

			Files.walkFileTree(
				rootPath,
				new SimpleFileVisitor<>() {

					@Override
					public FileVisitResult visitFile(Path filePath, BasicFileAttributes attrs) throws IOException {
						String fileName = String.valueOf(filePath.getFileName());

						if (!fileName.endsWith(".java")) {
							return FileVisitResult.CONTINUE;
						}

						List<String> lines = Files.readAllLines(filePath);

						lines.forEach(line -> {
							if (line.startsWith("import javax")) {
								javaxImports.add(line);
							}
						});

						return FileVisitResult.CONTINUE;
					}


					@Override
					public FileVisitResult visitFileFailed(Path filePath, IOException ioException) throws IOException {
						ioException.printStackTrace(System.err);

						return FileVisitResult.CONTINUE;
					}
				});

			return javaxImports;
		}
	}

}