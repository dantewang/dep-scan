import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Stream;

public class JavaxChecker {

	public static void scan(Path portalPath, Path csvPath) throws IOException {
		Path path = Path.of(portalPath.toString(), "../javax.log");

		Files.deleteIfExists(path);

		List<String> lines = Files.readAllLines(csvPath);

		Set<String> checkedDependencies = new LinkedHashSet<>();

		for (String line : lines) {
			String[] dependencyColumns = line.split(",");

			if (dependencyColumns.length != 5) {
				continue;
			}

			String dependency = dependencyColumns[0] + ":" + dependencyColumns[1] + ":" + dependencyColumns[2];

			if (dependencyColumns[1].startsWith("com.liferay")) {
				continue;
			}

			if (!checkedDependencies.add(dependency)) {
				continue;
			}

			Path jarPath = _getDependency(portalPath, dependencyColumns, "");

			if (jarPath == null) {
				continue;
			}

			List<String> javaxImports = _checkJar(jarPath);

			if (javaxImports == null) {
				Path sourceJarPath = _getDependency(portalPath, dependencyColumns, "-sources");

				if (sourceJarPath == null) {
					continue;
				}

				javaxImports = _checkSourceJar(sourceJarPath);
			}

			if (javaxImports.isEmpty()) {
				continue;
			}

			System.out.println("Found javax for " + dependency);

			try (PrintWriter printWriter = new PrintWriter(Files.newBufferedWriter(path, StandardOpenOption.CREATE, StandardOpenOption.APPEND))) {
				System.out.println(dependency);

				javaxImports.forEach(javaImport -> printWriter.println("\t" + javaImport));
			}
		}
	}

	private static Path _getDependency(Path portalPath, String[] dependencyColumns, String suffix) {
		Path jarPath = _findInLocalGradleCache(portalPath.resolve(".gradle/caches/modules-2/files-2.1"), dependencyColumns, suffix);

		if (jarPath != null) {
			return jarPath;
		}

		jarPath = _findInLocalGradleCache(Path.of(System.getProperty("user.home"), ".gradle/caches/modules-2/files-2.1"), dependencyColumns, suffix);

		if (jarPath != null) {
			return jarPath;
		}

		jarPath = _findInLocalMavenCache(Path.of(System.getProperty("user.home"), ".m2/repository"), dependencyColumns, suffix);

		if (jarPath != null) {
			return jarPath;
		}

		return _downloadDependency(dependencyColumns, suffix);
	}

	private static Path _downloadDependency(String[] dependencyColumns, String suffix) {
		String uri = _URL_BASE + dependencyColumns[0].replace('.', '/') + "/" + dependencyColumns[1] + "/" + dependencyColumns[2] + "/" + _getJarFileName(dependencyColumns, suffix);

		System.out.println("Downloading from " + uri);

		Path jarPath = Path.of("files", _getJarFileName(dependencyColumns, suffix));

		if (Files.exists(jarPath)) {
			return jarPath;
		}

		try {
			HttpResponse<Path> httpResponse = _httpClient.send(HttpRequest.newBuilder().GET().uri(URI.create(uri)).build(), HttpResponse.BodyHandlers.ofFile(jarPath));

			return httpResponse.body();
		}
		catch (Exception exception) {
			System.out.println(exception.getMessage());
		}

		return null;
	}

	private static Path _findInLocalMavenCache(Path mavenCachePath, String[] dependencyColumns, String suffix) {
		Path jarPath = Path.of(
			mavenCachePath.toString(), dependencyColumns[0].replace('.', '/'), dependencyColumns[1], dependencyColumns[2],
			dependencyColumns[1] + "-" + dependencyColumns[2] + suffix + ".jar");

		if (Files.exists(jarPath)) {
			return jarPath;
		}

		return null;
	}

	private static String _getJarFileName(String[] dependencyColumns, String suffix) {
		return dependencyColumns[1] + "-" + dependencyColumns[2] + suffix + ".jar";
	}

	private static Path _findInLocalGradleCache(Path gradleCachePath, String[] dependencyColumns, String suffix) {
		try (Stream<Path> stream = Files.walk(Path.of(gradleCachePath.toString(), dependencyColumns[0], dependencyColumns[1], dependencyColumns[2]))) {
			return stream.filter(
				path -> {
					String fileName = String.valueOf(path.getFileName());

					String expectedFileName = _getJarFileName(dependencyColumns, suffix);

					return expectedFileName.equals(fileName);
				}
			).findFirst(
			).orElse(
				null
			);
		}
		catch (Exception exception) {
			System.out.println(exception.getMessage());
		}

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
					public FileVisitResult visitFileFailed(Path filePath, IOException ioException) {
						ioException.printStackTrace(System.err);

						return FileVisitResult.CONTINUE;
					}
				});

			return javaxImports;
		}
	}

	private static final String _URL_BASE = "https://repo1.maven.org/maven2/";

	private static final HttpClient _httpClient = HttpClient.newBuilder().build();

}