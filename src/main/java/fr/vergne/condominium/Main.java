package fr.vergne.condominium;

import static java.nio.file.Files.createDirectories;
import static java.nio.file.Files.find;
import static java.nio.file.Files.isRegularFile;
import static java.util.stream.Collectors.joining;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

import fr.vergne.condominium.core.diagram.Diagram;
import fr.vergne.condominium.core.history.MailHistory;
import fr.vergne.condominium.core.mail.Header;
import fr.vergne.condominium.core.mail.Mail;
import fr.vergne.condominium.core.mail.Mail.Body;
import fr.vergne.condominium.core.mail.MimeType;
import fr.vergne.condominium.core.mail.SoftReferencedMail;
import fr.vergne.condominium.core.monitorable.Issue;
import fr.vergne.condominium.core.monitorable.Issue.State;
import fr.vergne.condominium.core.monitorable.Monitorable.History;
import fr.vergne.condominium.core.monitorable.Question;
import fr.vergne.condominium.core.parser.mbox.MBoxParser;
import fr.vergne.condominium.core.parser.yaml.IssueYamlSerializer;
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
import fr.vergne.condominium.core.util.RefinerIdSerializer;
import fr.vergne.condominium.core.util.Serializer;

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

		Repository<MailId, Mail> mailRepository = createMailRepository(mailRepositoryPath);

		LOGGER.accept("--- UPDATE ---");
//		updateMailsExceptRemovals(loadMBox(mboxPath, confMailCleaningPath), mailRepository);
		LOGGER.accept("--- /UPDATE ---");

		Source.Tracker sourceTracker = Source.Tracker.create(Source::create, Source.Refiner::create);
		Source<Repository<MailId, Mail>> mailRepoSource = sourceTracker.createSource(mailRepository);
		Source.Refiner<Repository<MailId, Mail>, MailId, Mail> mailRefiner = sourceTracker
				.createRefiner(Repository<MailId, Mail>::mustGet);

		Serializer<Source<?>, String> sourceSerializer = Serializer.createFromMap(Map.of(mailRepoSource, "mails"));
		Serializer<Refiner<?, ?, ?>, String> refinerSerializer = Serializer.createFromMap(Map.of(mailRefiner, "id"));
		RefinerIdSerializer refinerIdSerializer = createRefinerIdSerializer(mailRefiner);
		Serializer<Issue, String> issueSerializer = IssueYamlSerializer.create(sourceTracker::trackOf, sourceSerializer,
				refinerSerializer, refinerIdSerializer);
		Repository.Updatable<IssueId, Issue> issueRepository = createIssueRepository(issueRepositoryPath,
				issueSerializer);
		LOGGER.accept("=================");
		List<Mail> mails = mailRepository.streamResources().toList();
		{
			LOGGER.accept("Associate mails to issues");
			// TODO Notify issue with email attachment
			// TODO Notify issue with email section
			LOGGER.accept("**********************");

			int skip = 11;
			MailId mailId = mailRepository.streamKeys().skip(skip).findFirst().orElseThrow();
			Source<Mail> mailSource = mailRepoSource.refine(mailRefiner, mailId);
			Mail mail = mailSource.resolve();
			DateTimeFormatter dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
			LOGGER.accept("Date: " + dateFormatter.format(mail.receivedDate()));
			LOGGER.accept("From: " + mail.sender());
			LOGGER.accept("To: " + mail.receivers().toList());
			LOGGER.accept("Subject: " + mail.subject());
			LOGGER.accept("Body:");
			LOGGER.accept(getPlainOrHtmlBody(mail).text());

			LOGGER.accept("Issues:");
			issueRepository.streamResources().forEach(issue -> {
				String dateTime = dateFormatter.format(issue.dateTime());
				String title = issue.title();
				long historySize = issue.history().stream().count();
				LOGGER.accept("- (" + dateTime + ") " + title + " [" + historySize + "]");
			});
			LOGGER.accept("");

			// TODO Create GUI
			ZonedDateTime rootDateTime = ZonedDateTime.of(1999, 1, 1, 0, 0, 0, 0, ZoneId.systemDefault());
			Issue issue = Issue.create("-", rootDateTime, History.createEmpty());
			Stream.of(State.values()).forEach(status -> issue.notify(status, mail.receivedDate(), mailSource));
			issueRepository.key(issue).ifPresentOrElse(//
					id -> issueRepository.update(id, issue), //
					() -> issueRepository.add(issue)//
			);
		}

		createIssuesFromMails(mailRepository, mailRepoSource, mailRefiner, issueRepository);
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

	private static void createIssuesFromMails(Repository<MailId, Mail> mailRepository,
			Source<Repository<MailId, Mail>> mailRepoSource,
			Source.Refiner<Repository<MailId, Mail>, MailId, Mail> mailRefiner,
			Repository.Updatable<IssueId, Issue> issueRepository) {

		/*
		 * get all issues and put them in a map
		 */
		LOGGER.accept(" (" + new Date().toString() + ") - " + "Creating issues from mails");
		Stream<Entry<IssueId, Issue>> allIssues = issueRepository.stream();
		LOGGER.accept(" (" + new Date().toString() + ") - " + "put all existing issues in a map");
		Map<String, Entry<IssueId, Issue>> issuesMap = new HashMap<String, Entry<IssueId, Issue>>();
		allIssues.forEach(entry -> {
			issuesMap.put(entry.getValue().title(), entry);
		});

		List<String> excludedLabels = Arrays.asList("Messages archivés", "Messages envoyés", "Ouvert", "Important",
				"Catégorie : E-mails personnels", "Boîte de réception", "Catégorie : Mises à jour",
				"Catégorie : Promotions", "Non lus", "Corbeille");

		LOGGER.accept(" (" + new Date().toString() + ") - " + "Loop overs all mails");
		mailRepository.stream().forEach(entry -> {
			MailId mailId = entry.getKey();
			Mail mail = entry.getValue();
			// get mail label to use it as a title for Issue
			String mailLabels = mail.headers().tryGet("X-Gmail-Labels").get().body();
			String[] labels = mailLabels.split(",");
			Source<Mail> mailSource = mailRepoSource.refine(mailRefiner, mailId);
			ZonedDateTime mailReceivedDate = mail.receivedDate();

			Arrays.stream(labels).filter(mailLabel -> !excludedLabels.contains(mailLabel)).forEach(mailLabel -> {
				LOGGER.accept(" (" + new Date().toString() + ") - " + "update map with label : " + mailLabel);
				Issue issue = null;

				// check if an issue already exist with the same datetime, otherwise create a
				// new one
				if (issuesMap.containsKey(mailLabel)) {
					issue = issuesMap.get(mailLabel).getValue();
				} else {
					issue = Issue.create(mailLabel, mailReceivedDate, History.createEmpty());
					IssueId issueId = new IssueId(mailReceivedDate);
					issuesMap.put(mailLabel, Map.entry(issueId, issue));
				}

				// Try to remove mail from issue history and then add, to not have it twice
				issue.history().remove(new History.Item<Issue.State>(mailReceivedDate, Issue.State.INFO, mailSource));
				issue.history().add(new History.Item<Issue.State>(mailReceivedDate, Issue.State.INFO, mailSource));

			});

		});

		LOGGER.accept(" (" + new Date().toString() + ") - " + "add/update all issues from the map");
		issuesMap.values().forEach(entry -> {
			if (issueRepository.has(entry.getKey())) {
				issueRepository.update(entry.getKey(), entry.getValue());
			} else {
				issueRepository.add(entry.getValue());
			}
		});

	}
	
	static RefinerIdSerializer createRefinerIdSerializer(
			Source.Refiner<Repository<MailId, Mail>, MailId, Mail> mailRefiner) {
		DateTimeFormatter dateParser = DateTimeFormatter.ISO_DATE_TIME;
		return new RefinerIdSerializer() {

			@Override
			public <I> Object serialize(Refiner<?, I, ?> refiner, I id) {
				if (id instanceof MailId mailId) {
					return Map.of(//
							"dateTime", dateParser.format(mailId.datetime), //
							"email", mailId.sender//
					);
				} else {
					throw new RuntimeException("Not supported: " + id + " for refiner " + refiner);
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
					throw new RuntimeException("Not supported: " + serial + " for refiner " + refiner);
				}
			}
		};
	}

	public static Repository.Updatable<IssueId, Issue> createIssueRepository(Path repositoryPath,
			Serializer<Issue, String> issueSerializer) {
		Function<Issue, IssueId> identifier = issue -> new IssueId(issue.dateTime());

		Function<Issue, byte[]> resourceSerializer = issue -> {
			return issueSerializer.serialize(issue).getBytes();
		};
		Function<Supplier<byte[]>, Issue> resourceDeserializer = bytes -> {
			return issueSerializer.deserialize(new String(bytes.get()));
		};

		String extension = ".issue";
		try {
			createDirectories(repositoryPath);
		} catch (IOException cause) {
			throw new RuntimeException("Cannot create issue repository directory: " + repositoryPath, cause);
		}
		Function<IssueId, Path> pathResolver = (id) -> {
			String datePart = DateTimeFormatter.ISO_LOCAL_DATE.format(id.dateTime()).replace('-', File.separatorChar);
			Path dayDirectory = repositoryPath.resolve(datePart);
			try {
				createDirectories(dayDirectory);
			} catch (IOException cause) {
				throw new RuntimeException("Cannot create issue directory: " + dayDirectory, cause);
			}

			String timePart = DateTimeFormatter.ISO_LOCAL_TIME.format(id.dateTime()).replace(':', '-');
			return dayDirectory.resolve(timePart + extension);
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

	public static Repository.Updatable<QuestionId, Question> createQuestionRepository(Path repositoryPath,
			Serializer<Question, String> questionSerializer) {
		Function<Question, QuestionId> identifier = question -> new QuestionId(question.dateTime());

		Function<Question, byte[]> resourceSerializer = question -> {
			return questionSerializer.serialize(question).getBytes();
		};
		Function<Supplier<byte[]>, Question> resourceDeserializer = bytes -> {
			return questionSerializer.deserialize(new String(bytes.get()));
		};

		String extension = ".question";
		try {
			createDirectories(repositoryPath);
		} catch (IOException cause) {
			throw new RuntimeException("Cannot create question repository directory: " + repositoryPath, cause);
		}
		Function<QuestionId, Path> pathResolver = (id) -> {
			String datePart = DateTimeFormatter.ISO_LOCAL_DATE.format(id.dateTime()).replace('-', File.separatorChar);
			Path dayDirectory = repositoryPath.resolve(datePart);
			try {
				createDirectories(dayDirectory);
			} catch (IOException cause) {
				throw new RuntimeException("Cannot create question directory: " + dayDirectory, cause);
			}

			String timePart = DateTimeFormatter.ISO_LOCAL_TIME.format(id.dateTime()).replace(':', '-');
			return dayDirectory.resolve(timePart + extension);
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

	private static void updateMailsExceptRemovals(Repository<MailId, Mail> mboxRepository,
			Repository<MailId, Mail> mailRepository) {
		RepositoryDiff.of(mailRepository, mboxRepository)//
				.stream()//
				.peek(diff -> {
					Values<MailId, Mail> values = diff.values();
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

	private static Repository<MailId, Mail> loadMBox(Path mboxPath, Path confMailCleaningPath) {
		MBoxParser parser = new MBoxParser(LOGGER);
		MailCleaningConfiguration confMailCleaning = MailCleaningConfiguration.parser().apply(confMailCleaningPath);
		Repository<MailId, Mail> mboxRepository = new MemoryRepository<>(MailId::fromMail, new LinkedHashMap<>());
		parser.parseMBox(mboxPath)//
				.filter(on(confMailCleaning))//
				// .limit(40)// TODO Remove
				.peek(displayMailOn(LOGGER))//
				// .sorted(comparing(Mail::receivedDate))//
				.forEach(mboxRepository::add);
		return mboxRepository;
	}

	record IssueId(ZonedDateTime dateTime) {
	}

	record QuestionId(ZonedDateTime dateTime) {
	}

	record MailId(ZonedDateTime datetime, String sender) {
		public static MailId fromMail(Mail mail) {
			return new MailId(mail.receivedDate(), mail.sender().email());
		}
	}

	static FileRepository<MailId, Mail> createMailRepository(Path repositoryPath) {
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

	public static Mail.Body.Textual getPlainOrHtmlBody(Mail mail) {
		return (Mail.Body.Textual) Stream.of(mail.body())//
				.flatMap(Main::flattenRecursively)//
				.filter(body -> {
					return body.mimeType().equals(MimeType.Text.PLAIN) //
							|| body.mimeType().equals(MimeType.Text.HTML);
				}).findFirst().orElseThrow();
	}

	public static Stream<? extends Body> flattenRecursively(Body body) {
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
