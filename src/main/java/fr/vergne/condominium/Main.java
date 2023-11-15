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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

import fr.vergne.condominium.core.diagram.Diagram;
import fr.vergne.condominium.core.history.MailHistory;
import fr.vergne.condominium.core.issue.Issue;
import fr.vergne.condominium.core.mail.Header;
import fr.vergne.condominium.core.mail.SoftReferencedMail;
import fr.vergne.condominium.core.mail.Mail;
import fr.vergne.condominium.core.mail.Mail.Address;
import fr.vergne.condominium.core.mail.Mail.Body;
import fr.vergne.condominium.core.mail.MimeType;
import fr.vergne.condominium.core.parser.mbox.MBoxParser;
import fr.vergne.condominium.core.parser.yaml.MailCleaningConfiguration;
import fr.vergne.condominium.core.parser.yaml.PlotConfiguration;
import fr.vergne.condominium.core.parser.yaml.ProfilesConfiguration;
import fr.vergne.condominium.core.repository.FileRepository;
import fr.vergne.condominium.core.repository.MemoryRepository;
import fr.vergne.condominium.core.repository.Repository;
import fr.vergne.condominium.core.repository.RepositoryDiff;
import fr.vergne.condominium.core.repository.RepositoryDiff.ResourceDiff;

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

		MBoxParser parser = new MBoxParser(LOGGER);
		MailCleaningConfiguration confMailCleaning = MailCleaningConfiguration.parser().apply(confMailCleaningPath);
		Repository<Mail, MailId> mboxRepository = new MemoryRepository<>(MailId::fromMail, new LinkedHashMap<>());
		List<Mail> mails = parser.parseMBox(mboxPath)//
				.filter(on(confMailCleaning))//
				.peek(displayMailOn(LOGGER))//
				// .sorted(comparing(Mail::receivedDate))//
				.peek(mboxRepository::add)//
				// .limit(3)// TODO Remove
				.toList();

		Repository<Mail, MailId> mailRepository = createMailRepository(mailRepositoryPath);
		LOGGER.accept("--- DIFF ---");
		RepositoryDiff<Mail, MailId> repoDiff = RepositoryDiff.diff(mailRepository, mboxRepository);
		report(repoDiff);
		LOGGER.accept("--- /DIFF ---");
		repoDiff.apply(mailRepository.decorate()//
				.postAdd((id, mail) -> LOGGER.accept("Imported mail " + id))//
				.preDel((id) -> {
					// TODO Ignore removals
					throw new RuntimeException("Reject removal of " + id);
				})//
				.build());

		LOGGER.accept("=================");
		{
			LOGGER.accept("Associate mails to issues");
			// TODO Retrieve mail from mail repository through ID
			// TODO Update issue status from email
			// TODO Notify with email section
			// TODO Issue repository
			LOGGER.accept("**********************");
			Mail mail = mailRepository.streamResources()//
					.sorted(comparing(Mail::receivedDate))//
					.skip(2)//
					.findFirst().get();
			LOGGER.accept("From: " + mail.sender());
			LOGGER.accept("To: " + mail.receivers().toList());
			LOGGER.accept("At: " + mail.receivedDate());
			LOGGER.accept("Subject: " + mail.subject());
			LOGGER.accept("Body:");
			LOGGER.accept(reduceToPlainOrHtmlBody(mail).text());
			Issue issue = Issue.create("Panne de chauffage");
			issue.notifyByMail(mail, Issue.Status.REPORTED);
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

	private static void report(RepositoryDiff<Mail, MailId> mailRepositoryDiff) {
		Comparator<MailId> mailIdComparator = comparing(MailId::datetime).thenComparing(MailId::sender,
				comparing(Address::email));
		Map<MailId, Mail> addMap = new TreeMap<>(mailIdComparator);
		Map<MailId, Mail> delMap = new TreeMap<>(mailIdComparator);
		Map<MailId, Map.Entry<Mail, Mail>> resMap = new TreeMap<>(mailIdComparator);
		Map<Mail, Map.Entry<MailId, MailId>> keyMap = new TreeMap<>(comparing(MailId::fromMail, mailIdComparator));

		Consumer<ResourceDiff<Mail, MailId>> additionListener = resDiff -> addMap.put(resDiff.newKey(),
				resDiff.newResource());
		Consumer<ResourceDiff<Mail, MailId>> removalListener = resDiff -> delMap.put(resDiff.oldKey(),
				resDiff.oldResource());
		Consumer<ResourceDiff<Mail, MailId>> replacementListener = resDiff -> resMap.put(resDiff.oldKey(),
				Map.entry(resDiff.oldResource(), resDiff.newResource()));
		Consumer<ResourceDiff<Mail, MailId>> reidentifyListener = resDiff -> keyMap.put(resDiff.oldResource(),
				Map.entry(resDiff.oldKey(), resDiff.newKey()));

		mailRepositoryDiff.collect(additionListener, removalListener, replacementListener, reidentifyListener);

		DateTimeFormatter datetimeFormatter = java.time.format.DateTimeFormatter.ISO_DATE_TIME;
		Function<MailId, String> idFormatter = id -> datetimeFormatter.format(id.datetime) + " by " + id.sender;
		Function<Mail, String> mailFormatter = mail -> '"' + mail.subject() + '"';
		delMap.entrySet().forEach(entry -> LOGGER.accept(
				"Remove " + mailFormatter.apply(entry.getValue()) + " at " + idFormatter.apply(entry.getKey())));
		addMap.entrySet().forEach(entry -> LOGGER
				.accept("Add " + mailFormatter.apply(entry.getValue()) + " at " + idFormatter.apply(entry.getKey())));
		resMap.entrySet()
				.forEach(entry -> LOGGER.accept("Replace " + mailFormatter.apply(entry.getValue().getKey()) + " by "
						+ mailFormatter.apply(entry.getValue().getValue()) + " at "
						+ idFormatter.apply(entry.getKey())));
		keyMap.entrySet()
				.forEach(entry -> LOGGER.accept("Move " + mailFormatter.apply(entry.getKey()) + " from "
						+ idFormatter.apply(entry.getValue().getKey()) + " to "
						+ idFormatter.apply(entry.getValue().getValue())));
	}

	record MailId(ZonedDateTime datetime, Address sender) {
		public static MailId fromMail(Mail mail) {
			return new MailId(mail.receivedDate(), mail.sender());
		}
	}

	private static FileRepository<Mail, MailId> createMailRepository(Path mailRepositoryPath) {
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

		String mailExtension = ".mail";
		try {
			createDirectories(mailRepositoryPath);
		} catch (IOException cause) {
			throw new RuntimeException("Cannot create mail repository directory: " + mailRepositoryPath, cause);
		}
		Function<MailId, Path> pathResolver = (id) -> {
			String datePart = DateTimeFormatter.ISO_LOCAL_DATE.format(id.datetime).replace('-', File.separatorChar);
			Path dayDirectory = mailRepositoryPath.resolve(datePart);
			try {
				createDirectories(dayDirectory);
			} catch (IOException cause) {
				throw new RuntimeException("Cannot create mail directory: " + dayDirectory, cause);
			}

			String timePart = DateTimeFormatter.ISO_LOCAL_TIME.format(id.datetime).replace(':', '-');
			String addressPart = id.sender.email().replaceAll("[^a-zA-Z0-9]+", "-");
			return dayDirectory.resolve(timePart + "_" + addressPart + mailExtension);
		};
		Supplier<Stream<Path>> pathFinder = () -> {
			try {
				return find(mailRepositoryPath, Integer.MAX_VALUE, (path, attr) -> {
					return path.toString().endsWith(mailExtension) && isRegularFile(path);
				});
			} catch (IOException cause) {
				throw new RuntimeException("Cannot browse " + mailRepositoryPath, cause);
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
