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

import fr.vergne.condominium.core.issue.Issue;
import fr.vergne.condominium.core.issue.Issue.History;
import fr.vergne.condominium.core.issue.Issue.Status;
import fr.vergne.condominium.core.source.Source;
import fr.vergne.condominium.core.source.Source.Refiner;
import fr.vergne.condominium.core.util.RefinerIdSerializer;
import fr.vergne.condominium.core.util.Serializer;

public interface IssueYamlSerializer {

	public static Serializer<Issue, String> create(Function<Source<?>, Source.Track> sourceTracker,
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
				Represent sourceRepresent = SourceYamlSerializer.createRepresent(sourceTracker, sourceSerializer,
						refinerSerializer, refinerIdSerializer, scalarNodeFactory, sequenceNodeFactory,
						javaBeanNodeFactory);

				representers.put(null, new Represent() {
					@Override
					public Node representData(Object data) {
						Issue issue = (Issue) data;

						List<NodeTuple> issueTuples = new LinkedList<>();
						issueTuples.add(titleTuple(issue.title()));
						issueTuples.add(dateTimeTuple(issue.dateTime()));
						issueTuples.add(historyTuple(issue.history()));

						return new MappingNode(Tag.MAP, issueTuples, defaultFlowStyle);
					}

					private NodeTuple titleTuple(String title) {
						return new NodeTuple(//
								new ScalarNode(Tag.STR, "title", null, null, ScalarStyle.PLAIN), //
								new ScalarNode(Tag.STR, title, null, null, ScalarStyle.PLAIN)//
						);
					}

					private NodeTuple historyTuple(Issue.History history) {
						List<Node> itemNodes = history.stream().map(item -> {
							Tag itemTag = new Tag("!" + item.status().name().toLowerCase());

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
		Constructor constructor = new Constructor(Issue.class, loaderOptions) {
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
						MappingNode issueNode = (MappingNode) node;
						var wrapper = new Object() {
							String title;
							ZonedDateTime dateTime;
							History history;
						};
						issueNode.getValue().forEach(tuple -> {
							String tupleKey = ((ScalarNode) tuple.getKeyNode()).getValue();
							if (tupleKey.equals("title")) {
								wrapper.title = extractTitle(tuple);
							} else if (tupleKey.equals("dateTime")) {
								wrapper.dateTime = extractDateTime(tuple);
							} else if (tupleKey.equals("history")) {
								wrapper.history = extractHistory(tuple);
							} else {
								throw new IllegalStateException("Not supported: " + tupleKey);
							}
						});
						return Issue.create(wrapper.title, wrapper.dateTime, wrapper.history);
					}

					private Issue.History extractHistory(NodeTuple issueTuple) {
						return ((SequenceNode) issueTuple.getValueNode()).getValue().stream()//
								.map(itemNode -> (MappingNode) itemNode)//
								.map(itemNode -> {
									Status status = extractIssueStatus(itemNode);
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
									return new Issue.History.Item(wrapper.dateTime, status, wrapper.source);
								}).collect(Issue.History::createEmpty, Issue.History::add, (h1, h2) -> {
									throw new RuntimeException("Not implermented");
								});
					}

					private Source<?> extractSource(NodeTuple tuple) {
						return (Source<?>) sourceConstruct.construct(tuple.getValueNode());
					}

					private Issue.Status extractIssueStatus(MappingNode itemNode) {
						return Issue.Status.valueOf(itemNode.getTag().getValue().substring(1).toUpperCase());
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
		return new Serializer<Issue, String>() {

			@Override
			public String serialize(Issue issue) {
				return yamlParser.dump(issue);
			}

			@Override
			public Issue deserialize(String yaml) {
				return yamlParser.load(yaml);
			}
		};
	}
}
