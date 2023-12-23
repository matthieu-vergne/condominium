package fr.vergne.condominium.core.parser.yaml;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Function;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.DumperOptions.FlowStyle;
import org.yaml.snakeyaml.DumperOptions.LineBreak;
import org.yaml.snakeyaml.DumperOptions.ScalarStyle;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.AbstractConstruct;
import org.yaml.snakeyaml.constructor.Construct;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.introspector.Property;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Represent;
import org.yaml.snakeyaml.representer.Representer;

import fr.vergne.condominium.core.monitorable.Monitorable;
import fr.vergne.condominium.core.source.Source;
import fr.vergne.condominium.core.source.Source.Refiner;
import fr.vergne.condominium.core.util.RefinerIdSerializer;
import fr.vergne.condominium.core.util.Serializer;

public interface MonitorableYamlSerializer {

	public static <M extends Monitorable<S>, S> Serializer<M, String> create(//
			Class<M> monitorableClass, Monitorable.Factory<M, S> monitorableFactory, //
			Serializer<S, String> stateSerializer, //
			Function<Source<?>, Source.Track> sourceTracker, Serializer<Source<?>, String> sourceSerializer, //
			Serializer<Refiner<?, ?, ?>, String> refinerSerializer, RefinerIdSerializer refinerIdSerializer) {
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
				Represent sourceRepresent = SourceYamlSerializer.createRepresent(sourceTracker, sourceSerializer,
						refinerSerializer, refinerIdSerializer, scalarNodeFactory, sequenceNodeFactory,
						javaBeanNodeFactory);

				representers.put(null, new Represent() {
					@Override
					public Node representData(Object data) {
						@SuppressWarnings("unchecked")
						M monitorable = (M) data;

						List<NodeTuple> tuples = new LinkedList<>();
						tuples.add(titleTuple(monitorable.title()));
						tuples.add(dateTimeTuple(monitorable.dateTime()));
						tuples.add(historyTuple(stateSerializer, monitorable.history()));

						return new MappingNode(Tag.MAP, tuples, defaultFlowStyle);
					}

					private NodeTuple titleTuple(String title) {
						return new NodeTuple(//
								new ScalarNode(Tag.STR, "title", null, null, ScalarStyle.PLAIN), //
								new ScalarNode(Tag.STR, title, null, null, ScalarStyle.PLAIN)//
						);
					}

					private NodeTuple historyTuple(Serializer<S, String> stateSerializer,
							Monitorable.History<S> history) {
						List<Node> itemNodes = history.stream().map(item -> {
							Tag itemTag = new Tag("!" + stateSerializer.serialize(item.state()));

							List<NodeTuple> itemTuples = new LinkedList<>();
							itemTuples.add(dateTimeTuple(item.dateTime()));
							itemTuples.add(sourceTuple(item.source()));

							return (Node) new MappingNode(itemTag, itemTuples, defaultFlowStyle);
						}).toList();

						return new NodeTuple(//
								new ScalarNode(Tag.STR, "history", null, null, ScalarStyle.PLAIN), //
								new SequenceNode(Tag.SEQ, itemNodes, defaultFlowStyle)//
						);
					}

					private NodeTuple dateTimeTuple(ZonedDateTime dateTime) {
						return new NodeTuple(//
								new ScalarNode(Tag.STR, "dateTime", null, null, ScalarStyle.PLAIN), //
								new ScalarNode(Tag.STR, dateParser.format(dateTime), null, null, ScalarStyle.PLAIN)//
						);
					}

					private NodeTuple sourceTuple(Source<?> source) {
						return new NodeTuple(//
								new ScalarNode(Tag.STR, "source", null, null, ScalarStyle.PLAIN), //
								sourceRepresent.representData(source)//
						);
					}
				});
			}
		};

		LoaderOptions loaderOptions = new LoaderOptions();
		Constructor constructor = new Constructor(monitorableClass, loaderOptions) {
			{
				Function<ScalarNode, String> scalarFactory = node -> {
					return constructScalar(node);
				};
				Function<Node, Object> javaBeanFactory = node -> {
					return getConstructor(node).construct(node);
				};
				Construct sourceConstruct = SourceYamlSerializer.createConstruct(sourceSerializer, refinerSerializer,
						refinerIdSerializer, dateParser, scalarFactory, javaBeanFactory);
				yamlConstructors.put(null, new AbstractConstruct() {

					@Override
					public Object construct(Node node) {
						MappingNode monitorableNode = (MappingNode) node;
						var wrapper = new Object() {
							String title;
							ZonedDateTime dateTime;
							Monitorable.History<S> history;
						};
						monitorableNode.getValue().forEach(tuple -> {
							String tupleKey = ((ScalarNode) tuple.getKeyNode()).getValue();
							if (tupleKey.equals("title")) {
								wrapper.title = extractTitle(tuple);
							} else if (tupleKey.equals("dateTime")) {
								wrapper.dateTime = extractDateTime(tuple);
							} else if (tupleKey.equals("history")) {
								wrapper.history = extractHistory(stateSerializer, tuple);
							} else {
								throw new IllegalStateException("Not supported: " + tupleKey);
							}
						});
						return monitorableFactory.createMonitorable(wrapper.title, wrapper.dateTime, wrapper.history);
					}

					private Monitorable.History<S> extractHistory(Serializer<S, String> stateSerializer,
							NodeTuple monitorableTuple) {
						return ((SequenceNode) monitorableTuple.getValueNode()).getValue().stream()//
								.map(itemNode -> (MappingNode) itemNode)//
								.map(itemNode -> {
									S status = extractState(stateSerializer, itemNode);
									var wrapper = new Object() {
										ZonedDateTime dateTime;
										Source<?> source;
									};
									itemNode.getValue().forEach(tuple -> {
										String tupleKey = ((ScalarNode) tuple.getKeyNode()).getValue();
										if (tupleKey.equals("dateTime")) {
											wrapper.dateTime = extractDateTime(tuple);
										} else if (tupleKey.equals("source")) {
											wrapper.source = extractSource(tuple);
										} else {
											throw new IllegalStateException("Not supported: " + tupleKey);
										}
									});
									return new Monitorable.History.Item<S>(wrapper.dateTime, status, wrapper.source);
								}).collect(Monitorable.History::createEmpty, Monitorable.History::add, (h1, h2) -> {
									throw new RuntimeException("Not implermented");
								});
					}

					private Source<?> extractSource(NodeTuple tuple) {
						return (Source<?>) sourceConstruct.construct(tuple.getValueNode());
					}

					private S extractState(Serializer<S, String> stateSerializer, MappingNode itemNode) {
						return stateSerializer.deserialize(itemNode.getTag().getValue().substring(1));
					}

					private String extractTitle(NodeTuple tuple) {
						return ((ScalarNode) tuple.getValueNode()).getValue();
					}

					private ZonedDateTime extractDateTime(NodeTuple tuple) {
						return ZonedDateTime.from(dateParser.parse(((ScalarNode) tuple.getValueNode()).getValue()));
					}
				});
			}
		};

		Yaml yamlParser = new Yaml(constructor, representer, dumpOptions);
		return new Serializer<M, String>() {

			@Override
			public String serialize(M monitorable) {
				return yamlParser.dump(monitorable);
			}

			@Override
			public M deserialize(String yaml) {
				return yamlParser.load(yaml);
			}
		};
	}
}
