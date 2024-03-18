package fr.vergne.condominium.core.parser.yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.function.Function;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.TypeDescription;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.nodes.Tag;

@SuppressWarnings("serial")
public class DistrConfiguration extends LinkedHashMap<String, Object> {

	public static Function<Path, DistrConfiguration> parser() {
		class LotsConfigurationConstructor extends Constructor {

			public LotsConfigurationConstructor(LoaderOptions loadingConfig) {
				super(DistrConfiguration.class, loadingConfig);
//				yamlConstructors.put(new Tag("!orFilter"), constructFromSequence(OrFilter::new));
				addTypeDescription(new TypeDescription(SetDefiner.class, new Tag("!set")));
				addTypeDescription(new TypeDescription(ResourceDefiner.class, new Tag("!resource")));
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
		Yaml yamlParser = new Yaml(new LotsConfigurationConstructor(new LoaderOptions()));
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

	public static class SetDefiner {
		public SetDefiner(String arg) {
			System.out.println(getClass().getSimpleName() + ": " + arg);
		}
	}

	public static class ResourceDefiner {
		public ResourceDefiner(String arg) {
			System.out.println(getClass().getSimpleName() + ": " + arg);
		}
	}
}
