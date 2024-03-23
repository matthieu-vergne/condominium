package fr.vergne.condominium.core.parser.yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

public class GraphConfiguration {
	private Map<String, Resource> resources;
	private String displayedLotRegex;
	private String mergedName;

	public Map<String, Resource> getResources() {
		return resources;
	}

	public void setResources(Map<String, Resource> resources) {
		this.resources = resources;
	}

	public String getDisplayedLotRegex() {
		return displayedLotRegex;
	}

	public void setDisplayedLotRegex(String displayedLotRegex) {
		this.displayedLotRegex = displayedLotRegex;
	}

	public String getMergedName() {
		return mergedName;
	}

	public void setMergedName(String mergedName) {
		this.mergedName = mergedName;
	}

	public static Function<Path, GraphConfiguration> parser() {
		Yaml yamlParser = new Yaml(new Constructor(GraphConfiguration.class, new LoaderOptions()));
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

	public static class Resource {
		private String mode;
		private String render;

		public String getMode() {
			return mode;
		}

		public void setMode(String mode) {
			this.mode = mode;
		}

		public String getRender() {
			return render;
		}

		public void setRender(String render) {
			this.render = render;
		}
	}
}
