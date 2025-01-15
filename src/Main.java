import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Main {

	public static void main(String[] args) throws IOException {
		System.out.println("Hello world!");

		if (args.length == 0) {
			args = new String[] {"/home/repo/liferay/liferay-portal"};
		}

		_scan(Path.of(args[0]));
	}

	private static void _scan(Path path) throws IOException {
		Map<String, Map<String, Map<String, List<String>>>> deps = new TreeMap<>();
		List<String> logs = new ArrayList<>() {

			@Override
			public boolean add(String element) {
				System.out.println(element);

				return super.add(element);
			}

		};

		Files.walkFileTree(
			path, new SimpleFileVisitor<>() {

				@Override
				public FileVisitResult preVisitDirectory(Path dirPath, BasicFileAttributes attrs) {
					Path relativeDirPath = path.relativize(dirPath);

					String dirName = relativeDirPath.toString();

					for (String excludedDir : _excludedDirs) {
						if (dirName.equals(excludedDir)) {
							logs.add("Skipping " + dirName);

							return FileVisitResult.SKIP_SUBTREE;
						}
					}

					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult visitFile(Path filePath, BasicFileAttributes attrs) throws IOException {
					String fileName = String.valueOf(filePath.getFileName());

					switch (fileName) {
						case "build.gradle", "build-buildscript.gradle" -> _scanBuildGradle(filePath, deps, logs);
						case "dependencies.properties" -> _scanDepsProperties(filePath, deps, logs);
						// case "ivy.xml" -> _scanIvyXml(filePath, deps, logs);
					}

					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult visitFileFailed(Path filePath, IOException ioException) {
					System.out.println("Failed to visit " + filePath);

					ioException.printStackTrace(System.out);

					return FileVisitResult.CONTINUE;
				}

			}
		);

		try (PrintWriter printWriter = new PrintWriter(Files.newBufferedWriter(Path.of(path.toString(), "../full.csv"), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))) {
			deps.forEach(
				(group, artifacts) -> artifacts.forEach(
					(name, versionToFiles) -> versionToFiles.forEach(
						(version, files) -> files.forEach(
							filePath -> printWriter.println(group + "," + name + "," + version + "," + filePath)))));
		}

		try (PrintWriter printWriter = new PrintWriter(Files.newBufferedWriter(Path.of(path.toString(), "../duplicated.csv"), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))) {
			deps.forEach(
				(group, artifacts) -> artifacts.forEach(
					(name, versionToFiles) -> {
						if (versionToFiles.size() > 1) {
							versionToFiles.forEach(
								(version, files) -> files.forEach(
									filePath -> printWriter.println(group + "," + name + "," + version + "," + filePath)));
						}
					}));
		}

		try (PrintWriter printWriter = new PrintWriter(Files.newBufferedWriter(Path.of(path.toString(), "../logs.log"), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))) {
			logs.forEach(printWriter::println);
		}
	}

	private static void _scanDepsProperties(Path filePath, Map<String, Map<String, Map<String, List<String>>>> deps, List<String> logs) throws IOException {
		logs.add("Scanning dependencies.properties file: " +filePath.toString());

		for (String line : Files.readAllLines(filePath)) {
			line = line.trim();

			String[] parts = line.split("=");

			if (parts.length != 2) {
				logs.add("\t[dependencies.properties] Line is not a key value pair: " + line);

				continue;
			}

			String[] dependencyParts = parts[1].split(":");

			if (dependencyParts.length != 3) {
				logs.add("\t[dependencies.properties] Line does not contain dependency: " + line);

				continue;
			}

			_populateDeps(deps, new String[] {dependencyParts[0], dependencyParts[1], dependencyParts[2], "dependency"}, filePath);
		}
	}

	private static void _scanBuildGradle(Path filePath, Map<String, Map<String, Map<String, List<String>>>> deps, List<String> logs) throws IOException {
		logs.add("Scanning Gradle file: " + filePath.toString());

		for (String line : Files.readAllLines(filePath)) {
			line = line.trim();

			Matcher matcher = _buildGradlePattern.matcher(line);

			if (!matcher.find()) {
				continue;
			}

			if (matcher.groupCount() != 4) {
				logs.add("\t[Gradle] Line does not contain correct dependency: " + line);

				continue;
			}

			_populateDeps(deps, new String[] {matcher.group(2), matcher.group(3), matcher.group(4), matcher.group(1)}, filePath);
		}
	}

	private static void _populateDeps(Map<String, Map<String, Map<String, List<String>>>> deps, String[] dependencyParts, Path filePath) {
		if (_excludedGroupNames.contains(dependencyParts[0])) {
			return;
		}

		var artifacts = deps.computeIfAbsent(dependencyParts[0], key -> new TreeMap<>());

		var versionToFiles = artifacts.computeIfAbsent(dependencyParts[1], key -> new TreeMap<>());

		var files = versionToFiles.computeIfAbsent(dependencyParts[2], key -> new ArrayList<>());

		files.add(dependencyParts[3] + "," + filePath.toString());
	}

	private static final List<String> _excludedDirs = List.of("modules/third-party", "workspaces");
	private static final Pattern _buildGradlePattern = Pattern.compile("(.+?)group:\\s\"(.+?)\",\\sname:\\s\"(.+?)\".+?version:\\s\"(.+?)\"");
	private static final List<String> _excludedGroupNames = List.of("com.liferay.portal");

}