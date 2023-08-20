package fr.vergne.condominium.core.parser.yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.function.Function;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import fr.vergne.condominium.core.util.Pair;

public class PlotConfiguration {
	private String title;
	private String defaultGroup;
	private LinkedHashMap<String, Pair<String>> subplots;
	private String noGroupName;

	public String getTitle() {
		return title;
	}

	public void setTitle(String title) {
		this.title = title;
	}

	public String getDefaultGroup() {
		return defaultGroup;
	}

	public void setDefaultGroup(String defaultGroup) {
		this.defaultGroup = defaultGroup;
	}

	public LinkedHashMap<String, Pair<String>> getSubplots() {
		return subplots;
	}

	public void setSubplots(LinkedHashMap<String, Pair<String>> subplots) {
		this.subplots = subplots;
	}

	public String getNoGroupName() {
		return noGroupName;
	}

	public void setNoGroupName(String noGroupName) {
		this.noGroupName = noGroupName;
	}

	public record Subplot(String group1, String group2) {
	}

	public static Function<Path, PlotConfiguration> parser() {
		Yaml yamlParser = new Yaml(new Constructor(PlotConfiguration.class, new LoaderOptions()));
		return path -> {
			File file = path.toFile();
			FileInputStream inputStream;
			try {
				inputStream = new FileInputStream(file);
			} catch (FileNotFoundException cause) {
				throw new RuntimeException("File not found: " + path, cause);
			}
			return yamlParser.load(inputStream);
		};
	}
}
