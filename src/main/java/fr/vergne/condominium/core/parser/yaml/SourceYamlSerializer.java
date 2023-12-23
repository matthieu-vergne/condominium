package fr.vergne.condominium.core.parser.yaml;

import java.time.format.DateTimeFormatter;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Function;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.DumperOptions.FlowStyle;
import org.yaml.snakeyaml.DumperOptions.LineBreak;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.AbstractConstruct;
import org.yaml.snakeyaml.constructor.Construct;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.introspector.Property;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Represent;
import org.yaml.snakeyaml.representer.Representer;

import fr.vergne.condominium.core.source.Source;
import fr.vergne.condominium.core.source.Source.Refiner;
import fr.vergne.condominium.core.util.RefinerIdSerializer;
import fr.vergne.condominium.core.util.Serializer;

public interface SourceYamlSerializer {

	public static Serializer<Source<?>, String> create(Function<Source<?>, Source.Track> sourceTracker,
			Serializer<Source<?>, String> sourceSerializer, Serializer<Refiner<?, ?, ?>, String> refinerSerializer,
			RefinerIdSerializer refinerIdSerializer) {
		DumperOptions dumpOptions = new DumperOptions();
		dumpOptions.setLineBreak(LineBreak.UNIX);
		dumpOptions.setSplitLines(false);
		dumpOptions.setDefaultFlowStyle(FlowStyle.BLOCK);

		DateTimeFormatter dateParser = DateTimeFormatter.ISO_DATE_TIME;

		Representer representer = new Representer(dumpOptions) {
			{
				Function<String, Node> scalarNodeFactory = string -> {
					return representScalar(Tag.STR, string);
				};
				Function<List<Node>, SequenceNode> sequenceNodeFactory = nodes -> {
					return new SequenceNode(Tag.SEQ, nodes, defaultFlowStyle);
				};
				Function<Object, Node> javaBeanNodeFactory = value -> {
					var bean = new Object() {
						public Object getValue() {
							return value;
						}
					};
					String propertyName = "value";
					Property property = getPropertyUtils().getProperty(bean.getClass(), propertyName);
					return representJavaBeanProperty(bean, property, bean.getValue(), null).getValueNode();
				};
				representers.put(null, createRepresent(sourceTracker, sourceSerializer, refinerSerializer,
						refinerIdSerializer, scalarNodeFactory, sequenceNodeFactory, javaBeanNodeFactory));
			}
		};

		LoaderOptions loaderOptions = new LoaderOptions();
		Constructor constructor = new Constructor(Source.class, loaderOptions) {
			{
				Function<ScalarNode, String> scalarFactory = node -> {
					return constructScalar(node);
				};
				Function<Node, Object> javaBeanFactory = node -> {
					return getConstructor(node).construct(node);
				};
				yamlConstructors.put(null, (Construct) createConstruct(sourceSerializer, refinerSerializer,
						refinerIdSerializer, dateParser, scalarFactory, javaBeanFactory));
			}

		};

		Yaml yamlParser = new Yaml(constructor, representer, dumpOptions);
		return new Serializer<Source<?>, String>() {

			@Override
			public String serialize(Source<?> source) {
				return yamlParser.dump(source);
			}

			@Override
			public Source<?> deserialize(String yaml) {
				return yamlParser.load(yaml);
			}
		};
	}

	public static Represent createRepresent(Function<Source<?>, Source.Track> sourceTracker,
			Serializer<Source<?>, String> sourceSerializer, Serializer<Refiner<?, ?, ?>, String> refinerSerializer,
			RefinerIdSerializer refinerIdSerializer, Function<String, Node> scalarNodeFactory,
			Function<List<Node>, SequenceNode> sequenceNodeFactory, Function<Object, Node> javaBeanNodeFactory) {
		return new Represent() {
			@Override
			public Node representData(Object data) {
				Source<?> source = (Source<?>) data;
				List<Node> nodes = new LinkedList<>();

				Source.Track list = sourceTracker.apply((Source<?>) source);
				Source.Track.Root node = list.root();
				String serial = sourceSerializer.serialize(node.source());
				Node serialNode = scalarNodeFactory.apply(serial);
				nodes.add(serialNode);

				Source.Track.Transitive current = node;
				while (current.hasTransition()) {
					Source.Track.Transition<?> transition = current.transition();
					Node refNode = refineNodeHelper(transition, refinerSerializer, refinerIdSerializer);
					nodes.add(refNode);
					current = transition;
				}

				return sequenceNodeFactory.apply(nodes);
			}

			private <I> Node refineNodeHelper(Source.Track.Transition<I> transition,
					Serializer<Refiner<?, ?, ?>, String> refinerSerializer, RefinerIdSerializer refIdSerializer) {
				Source.Refiner<?, I, ?> ref = transition.refiner();
				I refId = transition.id();
				Object serial = refIdSerializer.serialize(ref, refId);
				Node serialNode = javaBeanNodeFactory.apply(serial);
				serialNode.setTag(new Tag("!" + refinerSerializer.serialize(ref)));
				return serialNode;
			}
		};
	}

	public static Construct createConstruct(Serializer<Source<?>, String> sourceSerializer,
			Serializer<Refiner<?, ?, ?>, String> refinerSerializer, RefinerIdSerializer refinerIdSerializer,
			DateTimeFormatter dateParser, Function<ScalarNode, String> scalarFactory,
			Function<Node, Object> javaBeanFactory) {
		return new AbstractConstruct() {

			@Override
			public Object construct(Node node) {
				SequenceNode sequenceNode = (SequenceNode) node;
				List<Node> nodes = sequenceNode.getValue();
				Source<?>[] source = { null };

				Node rootNode = nodes.get(0);
				String rootId = (String) scalarFactory.apply((ScalarNode) rootNode);
				source[0] = sourceSerializer.deserialize(rootId);

				nodes.stream().skip(1).forEach(subnode -> {
					String name = subnode.getTag().getValue().substring(1);
					Refiner<?, ?, ?> ref = refinerSerializer.deserialize(name);
					subnode.setTag(Tag.MAP);
					Object value = javaBeanFactory.apply(subnode);
					Object id = refinerIdSerializer.deserialize(ref, value);
					refineHelper(source, ref, id);
				});

				return source[0];
			}

			@SuppressWarnings("unchecked")
			private <X, Y> void refineHelper(Source<?>[] source, Refiner<X, Y, ?> ref, Object id) {
				source[0] = ((Source<X>) source[0]).refine(ref, (Y) id);
			}
		};
	}
}
