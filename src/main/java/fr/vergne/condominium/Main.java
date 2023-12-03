package fr.vergne.condominium;

import static java.nio.file.Files.createDirectories;
import static java.nio.file.Files.find;
import static java.nio.file.Files.isRegularFile;
import static java.util.stream.Collectors.joining;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.DumperOptions.FlowStyle;
import org.yaml.snakeyaml.DumperOptions.LineBreak;
import org.yaml.snakeyaml.DumperOptions.ScalarStyle;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.AbstractConstruct;
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

import fr.vergne.condominium.core.diagram.Diagram;
import fr.vergne.condominium.core.history.MailHistory;
import fr.vergne.condominium.core.issue.Issue;
import fr.vergne.condominium.core.issue.Issue.History;
import fr.vergne.condominium.core.issue.Issue.Status;
import fr.vergne.condominium.core.mail.Header;
import fr.vergne.condominium.core.mail.Mail;
import fr.vergne.condominium.core.mail.Mail.Body;
import fr.vergne.condominium.core.mail.MimeType;
import fr.vergne.condominium.core.mail.SoftReferencedMail;
import fr.vergne.condominium.core.parser.mbox.MBoxParser;
import fr.vergne.condominium.core.parser.yaml.MailCleaningConfiguration;
import fr.vergne.condominium.core.parser.yaml.PlotConfiguration;
import fr.vergne.condominium.core.parser.yaml.ProfilesConfiguration;
import fr.vergne.condominium.core.repository.FileRepository;
import fr.vergne.condominium.core.repository.MemoryRepository;
import fr.vergne.condominium.core.repository.Repository;
import fr.vergne.condominium.core.repository.RepositoryDiff;
import fr.vergne.condominium.core.repository.RepositoryDiff.ResourceDiff.Action;
import fr.vergne.condominium.core.repository.RepositoryDiff.ResourceDiff.Values;
import fr.vergne.condominium.core.source.Source;
import fr.vergne.condominium.core.source.Source.Refiner;

public class Main {
	private static final Consumer<Object> LOGGER = System.out::println;

	public static void main(String[] args) throws IOException {
		Path importFolderPath = Paths.get(System.getProperty("importFolder"));
		Path confFolderPath = Paths.get(System.getProperty("confFolder"));
		Path outFolderPath = Paths.get(System.getProperty("outFolder"));
		outFolderPath.toFile().mkdirs();
		Path mboxPath = importFolderPath.resolve(System.getProperty("mbox"));
		Path confProfilesPath = confFolderPath.resolve("profiles.yaml");
		Path confMailCleaningPath = confFolderPath.resolve("mailCleaning.yaml");
		Path confPlotCsPath = confFolderPath.resolve("plotCs.yaml");
		Path confPlotSyndicPath = confFolderPath.resolve("plotSyndic.yaml");
		Path historyScriptPath = outFolderPath.resolve("graph.plantuml");
		Path historyPath = outFolderPath.resolve("graph.svg");
		Path plotCsPath = outFolderPath.resolve("graph2.png");
		Path plotSyndicPath = outFolderPath.resolve("graph3.png");
		Path mailRepositoryPath = outFolderPath.resolve("mails");
		Path issueRepositoryPath = outFolderPath.resolve("issues");

		Repository<Mail, MailId> mailRepository = createMailRepository(mailRepositoryPath);

		LOGGER.accept("--- UPDATE ---");
//		updateMailsExceptRemovals(loadMBox(mboxPath, confMailCleaningPath), mailRepository);
		LOGGER.accept("--- /UPDATE ---");

		Source.Tracker sourceTracker = Source.Tracker.create(Source::create, Source.Refiner::create);
		Source<Repository<Mail, MailId>> mailRepoSource = sourceTracker.createSource(mailRepository);
		Source.Refiner<Repository<Mail, MailId>, MailId, Mail> mailRefiner = sourceTracker
				.createRefiner(Repository<Mail, MailId>::mustGet);

		Persister<Source<?>> sourcePersister = new Persister<Source<?>>() {
			String mailRepoId = "mails";

			@Override
			public String serialize(Source<?> source) {
				if (mailRepoSource.equals(source)) {
					return mailRepoId;
				} else {
					throw new IllegalArgumentException("Not supported: " + source);
				}
			}

			@Override
			public Source<?> deserialize(String serial) {
				if (mailRepoId.equals(serial)) {
					return mailRepoSource;
				} else {
					throw new IllegalArgumentException("Not supported: " + serial);
				}
			}
		};
		Persister<Refiner<?, ?, ?>> refinerPersister = new Persister<Refiner<?, ?, ?>>() {
			String mailId = "id";

			@Override
			public String serialize(Refiner<?, ?, ?> refiner) {
				if (mailRefiner.equals(refiner)) {
					return mailId;
				} else {
					throw new IllegalArgumentException("Not supported: " + refiner);
				}
			}

			@Override
			public Refiner<?, ?, ?> deserialize(String serial) {
				if (mailId.equals(serial)) {
					return mailRefiner;
				} else {
					throw new IllegalArgumentException("Not supported: " + serial);
				}
			}
		};
		DateTimeFormatter dateParser = DateTimeFormatter.ISO_DATE_TIME;
		RefinerIdPersister refIdPersister = new RefinerIdPersister() {
			@Override
			public <I> Object serialize(Refiner<?, I, ?> refiner, I id) {
				if (id instanceof MailId mailId) {
					return Map.of(//
							"dateTime", dateParser.format(mailId.datetime), //
							"email", mailId.sender//
					);
				} else {
					throw new RuntimeException("Not supported: refiner " + refiner + " with ID " + id);
				}
			}

			@SuppressWarnings("unchecked")
			@Override
			public <I> I deserialize(Refiner<?, I, ?> refiner, Object serial) {
				if (refiner == mailRefiner) {
					Map<String, String> map = (Map<String, String>) serial;
					ZonedDateTime dateTime = ZonedDateTime.from(dateParser.parse(map.get("dateTime")));
					String email = map.get("email");
					return (I) new MailId(dateTime, email);
				} else {
					throw new RuntimeException("Not supported: refiner " + refiner + " with serial " + serial);
				}
			}
		};
		Repository<Issue, IssueId> issueRepository = createIssueRepository(//
				issueRepositoryPath, sourceTracker::trackOf, //
				sourcePersister, //
				refinerPersister, //
				refIdPersister//
		);
		LOGGER.accept("=================");
		List<Mail> mails = mailRepository.streamResources().toList();
		{
			LOGGER.accept("Associate mails to issues");
			// TODO Retrieve mail from ID
			// TODO Update issue status from email
			// TODO Notify with email section
			// TODO Issue repository
			LOGGER.accept("**********************");
			MailId mailId = mailRepository.streamKeys().findFirst().orElseThrow();
			Source<Mail> mailSource = mailRepoSource.refine(mailRefiner, mailId);
			Mail mail = mailSource.resolve();
			LOGGER.accept("From: " + mail.sender());
			LOGGER.accept("To: " + mail.receivers().toList());
			LOGGER.accept("At: " + mail.receivedDate());
			LOGGER.accept("Subject: " + mail.subject());
			LOGGER.accept("Body:");
			LOGGER.accept(reduceToPlainOrHtmlBody(mail).text());
			if (issueRepository.stream().count() == 0) {
				Issue issue = Issue.createEmpty("Panne de chauffage", mail.receivedDate());
				issue.notify(Issue.Status.REPORTED, mailSource, mail.receivedDate());
				IssueId issueId = issueRepository.add(issue);
				LOGGER.accept("Added: " + issueId);
			} else {
				IssueId issueId = issueRepository.streamKeys().findFirst().get();
				LOGGER.accept("Retrieving: " + issueId);
				Issue issue = issueRepository.mustGet(issueId);
				LOGGER.accept("Issue: " + issue);
				issue.history().stream().forEach(item -> {
					LOGGER.accept("- " + item);
					LOGGER.accept("> " + item.source().resolve());
				});
				// TODO Update repository
			}
		}

		String x;
		if ("".length() == 0)
			throw new RuntimeException("Check");

		LOGGER.accept("=================");

		LOGGER.accept("Read profiles conf");
		ProfilesConfiguration confProfiles = ProfilesConfiguration.parser().apply(confProfilesPath);

		LOGGER.accept("=================");
		{
			// TODO Filter on mail predicate
			LOGGER.accept("Create mail history");
			MailHistory.Factory mailHistoryFactory = new MailHistory.Factory.WithPlantUml(confProfiles, LOGGER);
			MailHistory mailHistory = mailHistoryFactory.create(mails);
			mailHistory.writeScript(historyScriptPath);
			mailHistory.writeSvg(historyPath);
			LOGGER.accept("Done");
		}

		LOGGER.accept("=================");
		int diagramWidth = 1000;
		int diagramHeightPerPlot = 250;
		Diagram.Factory diagramFactory = new Diagram.Factory.WithJFreeChart(confProfiles.getGroups());
		{
			LOGGER.accept("Read CS plot conf");
			PlotConfiguration confPlotCs = PlotConfiguration.parser().apply(confPlotCsPath);
			LOGGER.accept("Create plot");
			Diagram diagram = diagramFactory.ofSendReceive(confPlotCs).createDiagram(mails);
			diagram.writePng(plotCsPath, diagramWidth, diagramHeightPerPlot);
			LOGGER.accept("Done");
		}
		{
			LOGGER.accept("Read syndic plot conf");
			PlotConfiguration confPlotSyndic = PlotConfiguration.parser().apply(confPlotSyndicPath);
			LOGGER.accept("Create plot");
			Diagram diagram = diagramFactory.ofSendReceive(confPlotSyndic).createDiagram(mails);
			diagram.writePng(plotSyndicPath, diagramWidth, diagramHeightPerPlot);
			LOGGER.accept("Done");
		}
	}

	interface Persister<T> {
		String serialize(T object);

		T deserialize(String serial);
	}

	interface RefinerIdPersister {
		<I> Object serialize(Refiner<?, I, ?> refiner, I id);

		<I> I deserialize(Refiner<?, I, ?> refiner, Object serial);
	}

	private static <T> Repository<Issue, IssueId> createIssueRepository(//
			Path repositoryPath, //
			Function<Source<?>, Source.Track> sourceTracker, //
			Persister<Source<?>> sourcePersister, //
			Persister<Refiner<?, ?, ?>> refinerPersister, //
			RefinerIdPersister refinerIdPersister//
	) {
		Function<Issue, IssueId> identifier = issue -> new IssueId(issue);
		DateTimeFormatter dateParser = DateTimeFormatter.ISO_DATE_TIME;

		// TODO Compose Issue YAML parser with Source YAML parser
		DumperOptions dumpOptions = new DumperOptions();
		dumpOptions.setLineBreak(LineBreak.UNIX);
		dumpOptions.setSplitLines(false);
		dumpOptions.setDefaultFlowStyle(FlowStyle.BLOCK);
		Representer representer = new Representer(dumpOptions) {
			{
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
						List<Node> nodes = new LinkedList<>();

						Source.Track list = sourceTracker.apply(source);
						Source.Track.Root node = list.root();
						nodes.add(representScalar(Tag.STR, sourcePersister.serialize(node.source())));

						Source.Track.Transitive current = node;
						while (current.hasTransition()) {
							Source.Track.Transition<?> transition = current.transition();
							Node refNode = refineNodeHelper(transition, refinerPersister, refinerIdPersister);
							nodes.add(refNode);
							current = transition;
						}

						return new NodeTuple(//
								new ScalarNode(Tag.STR, "source", null, null, ScalarStyle.PLAIN), //
								new SequenceNode(Tag.SEQ, nodes, defaultFlowStyle)//
						);
					}

					private <I> Node refineNodeHelper(Source.Track.Transition<I> transition,
							Persister<Refiner<?, ?, ?>> refinerPersister, RefinerIdPersister refIdPersister) {
						Source.Refiner<?, I, ?> ref = transition.refiner();
						I refId = transition.id();
						Object serial = refIdPersister.serialize(ref, refId);
						var refObject = new Object() {
							public Object getSerial() {
								return serial;
							}
						};
						Property serialProperty = propertyOf(refObject, "serial");
						Node refNode = representJavaBeanProperty(refObject, serialProperty, refObject.getSerial(), null)
								.getValueNode();
						refNode.setTag(new Tag("!" + refinerPersister.serialize(ref)));
						return refNode;
					}

					private Property propertyOf(Object object, String name) {
						return getPropertyUtils().getProperty(object.getClass(), name);
					}
				});
			}
		};
		LoaderOptions loaderOptions = new LoaderOptions();
		loaderOptions.setTagInspector(tag -> true);
		Constructor constructor = new Constructor(Issue.class, loaderOptions) {
			{
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
						List<Node> nodes = ((SequenceNode) tuple.getValueNode()).getValue();
						Source<?>[] source = { null };

						Node rootNode = nodes.get(0);
						String rootId = (String) constructScalar((ScalarNode) rootNode);
						source[0] = sourcePersister.deserialize(rootId);

						nodes.stream().skip(1).forEach(subnode -> {
							String name = subnode.getTag().getValue().substring(1);
							Refiner<?, ?, ?> ref = refinerPersister.deserialize(name);
							subnode.setTag(Tag.MAP);
							Object value = getConstructor(subnode).construct(subnode);
							MailId id = (MailId) refinerIdPersister.deserialize(ref, value);
							refineHelper(source, ref, id);
						});

						return source[0];
					}

					@SuppressWarnings("unchecked")
					private <X, Y> void refineHelper(Source<?>[] source, Refiner<X, Y, ?> ref, Object id) {
						source[0] = ((Source<X>) source[0]).refine(ref, (Y) id);
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

		Function<Issue, byte[]> resourceSerializer = issue -> {
			return yamlParser.dump(issue).getBytes();
		};
		Function<Supplier<byte[]>, Issue> resourceDeserializer = bytes -> {
			return yamlParser.load(new String(bytes.get()));
		};

		String extension = ".issue";
		try {
			createDirectories(repositoryPath);
		} catch (IOException cause) {
			throw new RuntimeException("Cannot create issue repository directory: " + repositoryPath, cause);
		}
		Function<IssueId, Path> pathResolver = (id) -> {
			String datePart = DateTimeFormatter.ISO_LOCAL_DATE.format(id.issue().dateTime()).replace('-',
					File.separatorChar);
			Path dayDirectory = repositoryPath.resolve(datePart);
			try {
				createDirectories(dayDirectory);
			} catch (IOException cause) {
				throw new RuntimeException("Cannot create issue directory: " + dayDirectory, cause);
			}

			String timePart = DateTimeFormatter.ISO_LOCAL_TIME.format(id.issue().dateTime()).replace(':', '-');
			String addressPart = id.issue().title().replaceAll("[^a-zA-Z0-9]+", "-");
			return dayDirectory.resolve(timePart + "_" + addressPart + extension);
		};
		Supplier<Stream<Path>> pathFinder = () -> {
			try {
				return find(repositoryPath, Integer.MAX_VALUE, (path, attr) -> {
					return path.toString().endsWith(extension) && isRegularFile(path);
				}).sorted(Comparator.<Path>naturalOrder());// TODO Give real-time control over sort
			} catch (IOException cause) {
				throw new RuntimeException("Cannot browse " + repositoryPath, cause);
			}
		};
		return FileRepository.overBytes(//
				identifier, //
				resourceSerializer, resourceDeserializer, //
				pathResolver, pathFinder//
		);
	}

	private static void updateMailsExceptRemovals(Repository<Mail, MailId> mboxRepository,
			Repository<Mail, MailId> mailRepository) {
		RepositoryDiff.of(mailRepository, mboxRepository)//
				.stream()//
				.peek(diff -> {
					Values<Mail, MailId> values = diff.values();
					if (diff.is(Action.ADDITION)) {
						LOGGER.accept("Add \"" + values.newResource().subject() + "\" " + values.newKey());
					} else if (diff.is(Action.REMOVAL)) {
						LOGGER.accept("[pass] Remove \"" + values.oldResource().subject() + "\" " + values.oldKey());
					} else if (diff.is(Action.RESOURCE_REPLACEMENT)) {
						LOGGER.accept("Replace \"" + values.oldResource().subject() + "\" by \""
								+ values.newResource().subject() + "\" at " + values.oldKey());
					} else if (diff.is(Action.KEY_REPLACEMENT)) {
						LOGGER.accept("Reidentify \"" + values.oldResource().subject() + "\" from " + values.oldKey()
								+ " to " + values.newKey());
					} else {
						throw new RuntimeException("Not supported: " + diff);
					}
				})//
				.filter(diff -> !diff.is(Action.REMOVAL))//
				.forEach(diff -> diff.applyTo(mailRepository));
	}

	private static Repository<Mail, MailId> loadMBox(Path mboxPath, Path confMailCleaningPath) {
		MBoxParser parser = new MBoxParser(LOGGER);
		MailCleaningConfiguration confMailCleaning = MailCleaningConfiguration.parser().apply(confMailCleaningPath);
		Repository<Mail, MailId> mboxRepository = new MemoryRepository<>(MailId::fromMail, new LinkedHashMap<>());
		parser.parseMBox(mboxPath)//
				.filter(on(confMailCleaning))//
				// .limit(40)// TODO Remove
				.peek(displayMailOn(LOGGER))//
				// .sorted(comparing(Mail::receivedDate))//
				.forEach(mboxRepository::add);
		return mboxRepository;
	}

	record IssueId(Issue issue) {
	}

	record MailId(ZonedDateTime datetime, String sender) {
		public static MailId fromMail(Mail mail) {
			return new MailId(mail.receivedDate(), mail.sender().email());
		}
	}

	private static FileRepository<Mail, MailId> createMailRepository(Path repositoryPath) {
		Function<Mail, MailId> identifier = MailId::fromMail;

		MBoxParser repositoryParser = new MBoxParser(LOGGER);
		Function<Mail, byte[]> resourceSerializer = (mail) -> {
			return mail.lines().stream().collect(joining("\n")).getBytes();
		};
		Function<Supplier<byte[]>, Mail> resourceDeserializer = (bytesSupplier) -> {
			return new SoftReferencedMail(() -> {
				byte[] bytes = bytesSupplier.get();
				// Use split with negative limit to retain empty strings
				List<String> lines = Arrays.asList(new String(bytes).split("\n", -1));
				return repositoryParser.parseMail(lines);
			});
		};

		String extension = ".mail";
		try {
			createDirectories(repositoryPath);
		} catch (IOException cause) {
			throw new RuntimeException("Cannot create mail repository directory: " + repositoryPath, cause);
		}
		Function<MailId, Path> pathResolver = (id) -> {
			String datePart = DateTimeFormatter.ISO_LOCAL_DATE.format(id.datetime).replace('-', File.separatorChar);
			Path dayDirectory = repositoryPath.resolve(datePart);
			try {
				createDirectories(dayDirectory);
			} catch (IOException cause) {
				throw new RuntimeException("Cannot create mail directory: " + dayDirectory, cause);
			}

			String timePart = DateTimeFormatter.ISO_LOCAL_TIME.format(id.datetime).replace(':', '-');
			String addressPart = id.sender.replaceAll("[^a-zA-Z0-9]+", "-");
			return dayDirectory.resolve(timePart + "_" + addressPart + extension);
		};
		Supplier<Stream<Path>> pathFinder = () -> {
			try {
				return find(repositoryPath, Integer.MAX_VALUE, (path, attr) -> {
					return path.toString().endsWith(extension) && isRegularFile(path);
				}).sorted(Comparator.<Path>naturalOrder());// TODO Give real-time control over sort
			} catch (IOException cause) {
				throw new RuntimeException("Cannot browse " + repositoryPath, cause);
			}
		};

		return FileRepository.overBytes(//
				identifier, //
				resourceSerializer, resourceDeserializer, //
				pathResolver, pathFinder);
	}

	private static Mail.Body.Textual reduceToPlainOrHtmlBody(Mail mail) {
		return (Mail.Body.Textual) Stream.of(mail.body())//
				.flatMap(Main::flattenRecursively)//
				.filter(body -> {
					return body.mimeType().equals(MimeType.Text.PLAIN) //
							|| body.mimeType().equals(MimeType.Text.HTML);
				}).findFirst().orElseThrow();
	}

	private static Stream<? extends Body> flattenRecursively(Body body) {
		return body instanceof Mail.Body.Composed composed //
				? composed.bodies().stream().flatMap(Main::flattenRecursively) //
				: Stream.of(body);
	}

	private static Consumer<Mail> displayMailOn(Consumer<Object> logger) {
		int[] count = { 0 };
		return mail -> {
			logger.accept(count[0] + "> " + mail.lines().get(0) + " about: " + mail.subject());
			count[0]++;
		};
	}

	private static Predicate<Mail> on(MailCleaningConfiguration confMailCleaning) {
		Predicate<Mail> exclusionPredicate = confMailCleaning.getExclude().stream()//
				.map(exclusion -> {
					String header = exclusion.getHeader();
					String content = exclusion.getContains();
					return (Predicate<Mail>) mail -> {
						return mail.headers()//
								.tryGet(header).map(Header::body)//
								.map(body -> body.contains(content))//
								.orElse(false);
					};
				})//
				.reduce(Predicate::or)//
				.orElse(mail -> false);

		return exclusionPredicate.negate();
	}
}
