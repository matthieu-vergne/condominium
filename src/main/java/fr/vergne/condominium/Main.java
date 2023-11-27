package fr.vergne.condominium;

import static java.nio.file.Files.createDirectories;
import static java.nio.file.Files.find;
import static java.nio.file.Files.isRegularFile;
import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.joining;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

import fr.vergne.condominium.core.diagram.Diagram;
import fr.vergne.condominium.core.history.MailHistory;
import fr.vergne.condominium.core.issue.Issue;
import fr.vergne.condominium.core.mail.Header;
import fr.vergne.condominium.core.mail.Mail;
import fr.vergne.condominium.core.mail.Mail.Address;
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
		updateMailsExceptRemovals(loadMBox(mboxPath, confMailCleaningPath), mailRepository);
		LOGGER.accept("--- /UPDATE ---");

		List<Mail> mails = mailRepository.streamResources().toList();
		Source.Provider sourceProvider = createSourceProvider();
		Repository<Issue, IssueId> issueRepository = createIssueRepository(issueRepositoryPath);
		LOGGER.accept("=================");
		{
			LOGGER.accept("Associate mails to issues");
			// TODO Retrieve mail from ID
			// TODO Update issue status from email
			// TODO Notify with email section
			// TODO Issue repository
			LOGGER.accept("**********************");
			Mail mail = mails.stream()//
					.sorted(comparing(Mail::receivedDate))//
					.skip(2)//
					.peek(LOGGER)// TODO Remove
					.findFirst().get();
			LOGGER.accept("From: " + mail.sender());
			LOGGER.accept("To: " + mail.receivers().toList());
			LOGGER.accept("At: " + mail.receivedDate());
			LOGGER.accept("Subject: " + mail.subject());
			LOGGER.accept("Body:");
			LOGGER.accept(reduceToPlainOrHtmlBody(mail).text());
			Issue issue = Issue.create("Panne de chauffage", mail.receivedDate());
			IssueId issueId = issueRepository.add(issue);
			Source source = sourceProvider.sourceMail(mail);
			issue.notify(Issue.Status.REPORTED, source);
			// TODO Update repository
		}

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

	private static Repository<Issue, IssueId> createIssueRepository(Path repositoryPath) {
		Function<Issue, IssueId> identifier = issue -> new IssueId(issue);

		Function<Issue, byte[]> resourceSerializer = issue -> {
			// TODO Store YAML
			throw new RuntimeException("Not yet implemented");
		};
		Function<Supplier<byte[]>, Issue> resourceDeserializer = bytes -> {
			// TODO Read YAML
			throw new RuntimeException("Not yet implemented");
		};

		String extension = ".issue";
		try {
			createDirectories(repositoryPath);
		} catch (IOException cause) {
			throw new RuntimeException("Cannot create issue repository directory: " + repositoryPath, cause);
		}
		Function<IssueId, Path> pathResolver = (id) -> {
			String datePart = DateTimeFormatter.ISO_LOCAL_DATE.format(id.issue().datetime()).replace('-',
					File.separatorChar);
			Path dayDirectory = repositoryPath.resolve(datePart);
			try {
				createDirectories(dayDirectory);
			} catch (IOException cause) {
				throw new RuntimeException("Cannot create issue directory: " + dayDirectory, cause);
			}

			String timePart = DateTimeFormatter.ISO_LOCAL_TIME.format(id.issue().datetime()).replace(':', '-');
			String addressPart = id.issue().title().replaceAll("[^a-zA-Z0-9]+", "-");
			return dayDirectory.resolve(timePart + "_" + addressPart + extension);
		};
		Supplier<Stream<Path>> pathFinder = () -> {
			try {
				return find(repositoryPath, Integer.MAX_VALUE, (path, attr) -> {
					return path.toString().endsWith(extension) && isRegularFile(path);
				});
			} catch (IOException cause) {
				throw new RuntimeException("Cannot browse " + repositoryPath, cause);
			}
		};
		return new FileRepository<Issue, IssueId>(//
				identifier, //
				resourceSerializer, resourceDeserializer, //
				pathResolver, pathFinder//
		);
	}

	private static Source.Provider createSourceProvider() {
		return new Source.Provider() {

			@Override
			public Source sourceMail(Mail mail) {
				return new Source() {

					@Override
					public ZonedDateTime date() {
						return mail.receivedDate();
					}
				};
			}
		};
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

	record MailId(ZonedDateTime datetime, Address sender) {
		public static MailId fromMail(Mail mail) {
			return new MailId(mail.receivedDate(), mail.sender());
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
			String addressPart = id.sender.email().replaceAll("[^a-zA-Z0-9]+", "-");
			return dayDirectory.resolve(timePart + "_" + addressPart + extension);
		};
		Supplier<Stream<Path>> pathFinder = () -> {
			try {
				return find(repositoryPath, Integer.MAX_VALUE, (path, attr) -> {
					return path.toString().endsWith(extension) && isRegularFile(path);
				});
			} catch (IOException cause) {
				throw new RuntimeException("Cannot browse " + repositoryPath, cause);
			}
		};

		return new FileRepository<Mail, MailId>(//
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
