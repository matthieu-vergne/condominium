package fr.vergne.condominium.core.parser.yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.TypeDescription;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.nodes.Tag;

@SuppressWarnings("serial")
// TODO Use dedicated classes instead of mere maps
public class DistrConfiguration extends LinkedHashMap<String, Map<String, Map<String, Object>>> {

	public static Function<Path, DistrConfiguration> parser() {
		class DistrConfigurationConstructor extends Constructor {

			public DistrConfigurationConstructor(LoaderOptions loadingConfig) {
				super(DistrConfiguration.class, loadingConfig);
//				yamlConstructors.put(new Tag("!orFilter"), constructFromSequence(OrFilter::new));
				addTypeDescription(new TypeDescription(GroupValue.class, new Tag("!group")));
				addTypeDescription(new TypeDescription(ResourceValue.class, new Tag("!resource")));
			}

//			private <T> AbstractConstruct constructFromSequence(Function<List<T>, Object> constructor) {
//				return new AbstractConstruct() {
//
//					// TODO Proper check
//					@SuppressWarnings("unchecked")
//					@Override
//					public Object construct(Node node) {
//						return constructor.apply((List<T>) constructSequence((SequenceNode) node));
//					}
//				};
//			}

		}
		Yaml yamlParser = new Yaml(new DistrConfigurationConstructor(new LoaderOptions()));
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

	public static class GroupValue {
		private final String groupKey;
		private final String valueRef;

		public GroupValue(String arg) {
			String[] split = arg.split(" ");
			if (split.length != 2) {
				throw new IllegalArgumentException("Needs exactly 2 arguments, this is invalid: " + arg);
			}
			this.groupKey = split[0];
			this.valueRef = split[1];
		}

		public String getGroupKey() {
			return groupKey;
		}

		public String getValueRef() {
			return valueRef;
		}
	}

	public static class ResourceValue {
		private final String resourceKey;
		private final String valueRef;

		public ResourceValue(String arg) {
			String[] split = arg.split(" ");
			if (split.length != 2) {
				throw new IllegalArgumentException("Needs exactly 2 arguments, this is invalid: " + arg);
			}
			this.resourceKey = split[0];
			this.valueRef = split[1];
		}

		public String getResourceKey() {
			return resourceKey;
		}

		public String getValueRef() {
			return valueRef;
		}
	}
}
