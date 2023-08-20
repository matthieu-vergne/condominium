package fr.vergne.condominium.core.parser.yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

public class MailCleaningConfiguration {
	private List<Exclusion> exclude;

	public List<Exclusion> getExclude() {
		return exclude;
	}

	public void setExclude(List<Exclusion> exclude) {
		this.exclude = exclude;
	}

	public static Function<Path, MailCleaningConfiguration> parser() {
		Yaml yamlParser = new Yaml(new Constructor(MailCleaningConfiguration.class, new LoaderOptions()));
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

	public static class Exclusion {
		private String header;
		private String contains;

		public String getHeader() {
			return header;
		}

		public void setHeader(String header) {
			this.header = header;
		}

		public String getContains() {
			return contains;
		}

		public void setContains(String contains) {
			this.contains = contains;
		}
	}
}
