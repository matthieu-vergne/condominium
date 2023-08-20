package fr.vergne.condominium.core.parser.yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.TypeDescription;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.AbstractConstruct;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;

import fr.vergne.condominium.core.parser.yaml.Filter.EmailEndsWithFilter;
import fr.vergne.condominium.core.parser.yaml.Filter.EmailEqualsFilter;
import fr.vergne.condominium.core.parser.yaml.Filter.NameEqualsFilter;
import fr.vergne.condominium.core.parser.yaml.Filter.OrFilter;

public class ProfilesConfiguration {
	private List<Profile> individuals;
	private Map<String, Group> groups;

	public List<Profile> getIndividuals() {
		return individuals;
	}

	public void setIndividuals(List<Profile> individuals) {
		this.individuals = individuals;
	}

	public Map<String, Group> getGroups() {
		return groups;
	}

	public void setGroups(Map<String, Group> groups) {
		this.groups = groups;
	}

	public static Function<Path, ProfilesConfiguration> parser() {
		class ProfilesConfigurationConstructor extends Constructor {

			public ProfilesConfigurationConstructor(LoaderOptions loadingConfig) {
				super(ProfilesConfiguration.class, loadingConfig);
				yamlConstructors.put(new Tag("!orFilter"), constructFromSequence(OrFilter::new));
				addTypeDescription(new TypeDescription(NameEqualsFilter.class, new Tag("!nameEquals")));
				addTypeDescription(new TypeDescription(EmailEqualsFilter.class, new Tag("!emailEquals")));
				addTypeDescription(new TypeDescription(EmailEndsWithFilter.class, new Tag("!emailEndsWith")));
			}

			private <T> AbstractConstruct constructFromSequence(Function<List<T>, Object> constructor) {
				return new AbstractConstruct() {

					// TODO Proper check
					@SuppressWarnings("unchecked")
					@Override
					public Object construct(Node node) {
						return constructor.apply((List<T>) constructSequence((SequenceNode) node));
					}
				};
			}

		}
		Yaml yamlParser = new Yaml(new ProfilesConfigurationConstructor(new LoaderOptions()));
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

	public static class Profile {
		private List<String> names;
		private List<String> emails;

		public List<String> getNames() {
			return names;
		}

		public void setNames(List<String> names) {
			this.names = names;
		}

		public List<String> getEmails() {
			return emails;
		}

		public void setEmails(List<String> emails) {
			this.emails = emails;
		}
	}

	public static class Group {
		private Filter filter;

		public Filter getFilter() {
			return filter;
		}

		public void setFilter(Filter filter) {
			this.filter = filter;
		}
	}
}
