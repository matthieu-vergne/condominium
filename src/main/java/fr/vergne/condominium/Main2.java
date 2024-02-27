package fr.vergne.condominium;

import static java.nio.file.Files.createDirectories;
import static java.nio.file.Files.find;
import static java.nio.file.Files.isRegularFile;
import static java.util.Comparator.comparing;
import static java.util.Objects.requireNonNull;
import static java.util.function.Predicate.not;
import static java.util.stream.Collectors.joining;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import fr.vergne.condominium.core.mail.Mail;
import fr.vergne.condominium.core.mail.Mail.Body;
import fr.vergne.condominium.core.mail.MimeType;
import fr.vergne.condominium.core.mail.SoftReferencedMail;
import fr.vergne.condominium.core.monitorable.Issue;
import fr.vergne.condominium.core.monitorable.Question;
import fr.vergne.condominium.core.parser.mbox.MBoxParser;
import fr.vergne.condominium.core.parser.yaml.ProfilesConfiguration;
import fr.vergne.condominium.core.repository.FileRepository;
import fr.vergne.condominium.core.repository.Repository;
import fr.vergne.condominium.core.source.Source;
import fr.vergne.condominium.core.source.Source.Refiner;
import fr.vergne.condominium.core.util.RefinerIdSerializer;
import fr.vergne.condominium.core.util.Serializer;
import net.sourceforge.plantuml.FileFormat;
import net.sourceforge.plantuml.FileFormatOption;
import net.sourceforge.plantuml.SourceStringReader;

public class Main2 {
	private static final Consumer<Object> LOGGER = System.out::println;

	public static void main(String[] args) throws IOException {
		Path importFolderPath = Paths.get(System.getProperty("importFolder"));
		Path confFolderPath = Paths.get(System.getProperty("confFolder"));
		Path outFolderPath = Paths.get(System.getProperty("outFolder"));
		outFolderPath.toFile().mkdirs();
		Path confProfilesPath = confFolderPath.resolve("profiles.yaml");
		Path path = outFolderPath.resolve("graphCharges.plantuml");
		Path svgPath = outFolderPath.resolve("graphCharges.svg");

		LOGGER.accept("=================");

		LOGGER.accept("Read profiles conf");
		ProfilesConfiguration confProfiles = ProfilesConfiguration.parser().apply(confProfilesPath);

		LOGGER.accept("=================");
		{
			// TODO Filter on condominium lots
			LOGGER.accept("Create charges diagram");
//			MailHistory.Factory mailHistoryFactory = new MailHistory.Factory.WithPlantUml(confProfiles, LOGGER);
//			MailHistory mailHistory = mailHistoryFactory.create(mails);
//			mailHistory.writeScript(historyScriptPath);
//			mailHistory.writeSvg(historyPath);

			/* STATIC INFO: MEASURED RESOURCES */

			String mwhKey = "elec";
			String waterKey = "eau";
			String eurosKey = "euros";

			List<String> resourceKeys = List.of(mwhKey, waterKey, eurosKey);

			Graph.PartialModel partialModel = new Graph.PartialModel(resourceKeys);

			/* OUTPUT */

			String lot32 = "Lot.32";
			String lot33 = "Lot.33";

			/* STATIC SOURCE & STATIC INFO */

			// TODO Retrieve lots tantiemes from CSV(Lots, PCg/s)
			String tantièmesPcs3 = "Tantiemes.PCS3";
			partialModel.dispatch(tantièmesPcs3).to(lot32).taking(Graph.PartialModel.tantiemes(317));
			partialModel.dispatch(tantièmesPcs3).to(lot33).taking(Graph.PartialModel.tantiemes(449));

			String tantièmesPcs4 = "Tantiemes.PCS4";
			partialModel.dispatch(tantièmesPcs4).to(lot32).taking(Graph.PartialModel.tantiemes(347));
			partialModel.dispatch(tantièmesPcs4).to(lot33).taking(Graph.PartialModel.tantiemes(494));

			String tantièmesChauffage = "Tantiemes.ECS_Chauffage";
			partialModel.dispatch(tantièmesChauffage).to(lot32).taking(Graph.PartialModel.tantiemes(127));
			partialModel.dispatch(tantièmesChauffage).to(lot33).taking(Graph.PartialModel.tantiemes(179));

			String tantièmesRafraichissement = "Tantiemes.Rafraichissement";
			partialModel.dispatch(tantièmesRafraichissement).to(lot32).taking(Graph.PartialModel.tantiemes(182));
			partialModel.dispatch(tantièmesRafraichissement).to(lot33).taking(Graph.PartialModel.tantiemes(256));

			String elecChaufferieCombustibleECSTantiemes = "Elec.Chaufferie.combustibleECSTantiemes";
			partialModel.dispatch(elecChaufferieCombustibleECSTantiemes).to(tantièmesChauffage).taking(Graph.PartialModel.everything());
			String elecChaufferieCombustibleECSCompteurs = "Elec.Chaufferie.combustibleECSCompteurs";
			String elecChaufferieCombustibleRCTantiemes = "Elec.Chaufferie.combustibleRCTantiemes";
			partialModel.dispatch(elecChaufferieCombustibleRCTantiemes).to(tantièmesChauffage).taking(Graph.PartialModel.ratio(0.5));
			partialModel.dispatch(elecChaufferieCombustibleRCTantiemes).to(tantièmesRafraichissement).taking(Graph.PartialModel.ratio(0.5));
			String elecChaufferieCombustibleRCCompteurs = "Elec.Chaufferie.combustibleRCCompteurs";
			String elecChaufferieAutreTantiemes = "Elec.Chaufferie.autreTantiemes";
			partialModel.dispatch(elecChaufferieAutreTantiemes).to(tantièmesChauffage).taking(Graph.PartialModel.ratio(0.5));
			partialModel.dispatch(elecChaufferieAutreTantiemes).to(tantièmesRafraichissement).taking(Graph.PartialModel.ratio(0.5));
			String elecChaufferieAutreMesures = "Elec.Chaufferie.autreMesures";

			/* STATIC SOURCE & DYNAMIC INFO */

			String eauPotableFroideLot32 = "Eau.Potable.Froide.lot32";
			partialModel.dispatch(eauPotableFroideLot32).to(lot32).taking(Graph.PartialModel.everything());
			String eauPotableFroideLot33 = "Eau.Potable.Froide.lot33";
			partialModel.dispatch(eauPotableFroideLot33).to(lot33).taking(Graph.PartialModel.everything());

			Graph.PartialModel.Group setECS = partialModel.createGroup();
			String eauPotableChaudeLot32 = "Eau.Potable.Chaude.lot32";
			partialModel.dispatch(eauPotableChaudeLot32).to(lot32).taking(Graph.PartialModel.everything());
			// TODO Introduce intermediary variables
			String compteurEcs32 = "Compteur.ECS.Lot.32";
			Graph.PartialModel.Calculation.Value compteurEcs32Var = partialModel.variable(compteurEcs32);
			double ecs32 = 10.0;
			partialModel.dispatch(elecChaufferieCombustibleECSCompteurs).to(eauPotableChaudeLot32).taking(setECS.part(ecs32));// TODO Dispatch up
			String eauPotableChaudeLot33 = "Eau.Potable.Chaude.lot33";
			partialModel.dispatch(eauPotableChaudeLot33).to(lot33).taking(Graph.PartialModel.everything());
			String compteurEcs33 = "Compteur.ECS.Lot.33";
			Graph.PartialModel.Calculation.Value compteurEcs33Var = partialModel.variable(compteurEcs33);
			double ecs33 = 10.0;
			partialModel.dispatch(elecChaufferieCombustibleECSCompteurs).to(eauPotableChaudeLot33).taking(setECS.part(ecs33));// TODO Dispatch up

			Graph.PartialModel.Group setCalorifique = partialModel.createGroup();
			String elecCalorifiqueLot32 = "Elec.Calorifique.lot32";
			partialModel.dispatch(elecCalorifiqueLot32).to(lot32).taking(Graph.PartialModel.everything());
			Graph.PartialModel.Calculation calorifique32 = setCalorifique.part(0.1);
			partialModel.dispatch(elecChaufferieCombustibleRCCompteurs).to(elecCalorifiqueLot32).taking(calorifique32);
			partialModel.dispatch(elecChaufferieAutreMesures).to(elecCalorifiqueLot32).taking(calorifique32);
			String elecCalorifiqueLot33 = "Elec.Calorifique.lot33";
			partialModel.dispatch(elecCalorifiqueLot33).to(lot33).taking(Graph.PartialModel.everything());
			Graph.PartialModel.Calculation calorifique33 = setCalorifique.part(0.1);
			partialModel.dispatch(elecChaufferieCombustibleRCCompteurs).to(elecCalorifiqueLot33).taking(calorifique33);
			partialModel.dispatch(elecChaufferieAutreMesures).to(elecCalorifiqueLot33).taking(calorifique33);

			String eauPotableChaufferie = "Eau.Potable.chaufferie";
			partialModel.dispatch(eauPotableChaufferie).to(eauPotableChaudeLot32).taking(Graph.PartialModel.resource(Graph.PartialModel.Calculation.Mode.WATER, waterKey, ecs32));
			partialModel.dispatch(eauPotableChaufferie).to(eauPotableChaudeLot33).taking(Graph.PartialModel.resource(Graph.PartialModel.Calculation.Mode.WATER, waterKey, ecs33));
			String eauPotableGeneral = "Eau.Potable.general";
			partialModel.dispatch(eauPotableGeneral).to(eauPotableChaufferie).taking(Graph.PartialModel.resource(Graph.PartialModel.Calculation.Mode.WATER, waterKey, 50.0));
			partialModel.dispatch(eauPotableGeneral).to(eauPotableFroideLot32).taking(Graph.PartialModel.resource(Graph.PartialModel.Calculation.Mode.WATER, waterKey, 0.1));
			partialModel.dispatch(eauPotableGeneral).to(eauPotableFroideLot33).taking(Graph.PartialModel.resource(Graph.PartialModel.Calculation.Mode.WATER, waterKey, 0.1));

			String elecChaufferieAutre = "Elec.Chaufferie.autre";
			partialModel.dispatch(elecChaufferieAutre).to(elecChaufferieAutreMesures).taking(Graph.PartialModel.ratio(0.5));
			partialModel.dispatch(elecChaufferieAutre).to(elecChaufferieAutreTantiemes).taking(Graph.PartialModel.ratio(0.5));
			String elecChaufferieCombustibleRC = "Elec.Chaufferie.combustibleRC";
			partialModel.dispatch(elecChaufferieCombustibleRC).to(elecChaufferieCombustibleRCTantiemes).taking(Graph.PartialModel.ratio(0.3));
			partialModel.dispatch(elecChaufferieCombustibleRC).to(elecChaufferieCombustibleRCCompteurs).taking(Graph.PartialModel.ratio(0.7));
			String elecChaufferieCombustibleECS = "Elec.Chaufferie.combustibleECS";
			partialModel.dispatch(elecChaufferieCombustibleECS).to(elecChaufferieCombustibleECSTantiemes).taking(Graph.PartialModel.ratio(0.3));
			partialModel.dispatch(elecChaufferieCombustibleECS).to(elecChaufferieCombustibleECSCompteurs).taking(Graph.PartialModel.ratio(0.7));
			String elecChaufferieCombustible = "Elec.Chaufferie.combustible";
			partialModel.dispatch(elecChaufferieCombustible).to(elecChaufferieCombustibleECS).taking(Graph.PartialModel.resource(Graph.PartialModel.Calculation.Mode.MWH, mwhKey, 15.0));
			partialModel.dispatch(elecChaufferieCombustible).to(elecChaufferieCombustibleRC).taking(Graph.PartialModel.resource(Graph.PartialModel.Calculation.Mode.MWH, mwhKey, 15.0));
			String elecChaufferie = "Elec.Chaufferie.general";
			partialModel.dispatch(elecChaufferie).to(elecChaufferieCombustible).taking(Graph.PartialModel.resource(Graph.PartialModel.Calculation.Mode.MWH, mwhKey, 30.0));
			partialModel.dispatch(elecChaufferie).to(elecChaufferieAutre).taking(Graph.PartialModel.resource(Graph.PartialModel.Calculation.Mode.MWH, mwhKey, 20.0));
			String elecTgbtAscenseurBoussole = "Elec.TGBT.ascenseur_boussole";
			partialModel.dispatch(elecTgbtAscenseurBoussole).to(tantièmesPcs3).taking(Graph.PartialModel.everything());
			String elecTgbtGeneral = "Elec.TGBT.general";
			partialModel.dispatch(elecTgbtGeneral).to(elecTgbtAscenseurBoussole).taking(Graph.PartialModel.resource(Graph.PartialModel.Calculation.Mode.MWH, mwhKey, 10.0));
			partialModel.dispatch(elecTgbtGeneral).to(elecChaufferie).taking(Graph.PartialModel.resource(Graph.PartialModel.Calculation.Mode.MWH, mwhKey, 50.0));

			/* DYNAMIC SOURCE & DYNAMIC INFO */

			Graph.CompleteModel completeModel = partialModel.complete();

			String factureElec = "Facture.Elec";
			completeModel.assign(factureElec, mwhKey, 100.0);
			completeModel.assign(factureElec, eurosKey, 1000.0);
			completeModel.link(factureElec, Graph.PartialModel.resource(Graph.PartialModel.Calculation.Mode.MWH, mwhKey, 100.0), elecTgbtGeneral);

			String factureWater = "Facture.Eau";
			completeModel.assign(factureWater, waterKey, 100.0);
			completeModel.assign(factureWater, eurosKey, 1000.0);
			completeModel.link(factureWater, Graph.PartialModel.resource(Graph.PartialModel.Calculation.Mode.WATER, waterKey, 100.0), eauPotableGeneral);

			String facturePoubellesBoussole = "Facture.PoubelleBoussole";
			completeModel.assign(facturePoubellesBoussole, eurosKey, 100.0);
			completeModel.link(facturePoubellesBoussole, completeModel.calculateFromAll(), tantièmesPcs4);

			Graph.Instance graphInstance = completeModel.instantiate();

			String title = "Charges";// "Charges " + DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(LocalDateTime.now());

			LOGGER.accept("Redact script");
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			PrintStream scriptStream = new PrintStream(out, false, Charset.forName("UTF-8"));
			scriptStream.println("@startuml");
			scriptStream.println("title \"" + title + "\"");
			scriptStream.println("left to right direction");
			Map<String, String> resourceRenderer = Map.of(//
					mwhKey, "MWh", //
					waterKey, "m³", //
					eurosKey, "€"//
			);
			Comparator<Graph.PartialModel.ID> idComparator = comparing(Graph.PartialModel.ID::value);
			Comparator<Graph.Instance.Node> nodeComparator = comparing(Graph.Instance.Node::id, idComparator);
			graphInstance.vertices()//
					// TODO Remove sort once tested
					.sorted(nodeComparator)//
					.forEach(node -> {
						scriptStream.println("map " + node.id().value() + " {");
						resourceKeys.forEach(resourceKey -> {
							scriptStream.println("	" + resourceRenderer.get(resourceKey) + " => " + node.get(resourceKey).map(Object::toString).orElse(""));
						});
						scriptStream.println("}");
					});
			graphInstance.edges()//
					// TODO Remove sort once tested
					.sorted(comparing(Graph.Instance.Link::source, nodeComparator).thenComparing(Graph.Instance.Link::target, nodeComparator))//
					.forEach(link -> {
						Graph.Instance.Resources result = link.result();
						Graph.PartialModel.Calculation.Mode mode = result.mode();
						String calculationString;
						if (mode == Graph.PartialModel.Calculation.Mode.RATIO) {
							calculationString = ((double) result.value() * 100) + " %";
						} else if (mode == Graph.PartialModel.Calculation.Mode.TANTIEMES) {
							calculationString = (int) result.value() + " t";
						} else if (mode == Graph.PartialModel.Calculation.Mode.MWH) {
							calculationString = (double) result.value() + " " + resourceRenderer.get(mwhKey);
						} else if (mode == Graph.PartialModel.Calculation.Mode.WATER) {
							calculationString = (double) result.value() + " " + resourceRenderer.get(waterKey);
						} else if (mode == Graph.PartialModel.Calculation.Mode.SET) {
							calculationString = (double) result.value() + " from set";
						} else {
							throw new IllegalArgumentException("Unsupported value: " + mode);
						}
						scriptStream.println(link.source().id().value() + " --> " + link.target().id().value() + " : " + calculationString);
					});
			scriptStream.println("@enduml");
			scriptStream.flush();
			String script = out.toString();

			try {
				Files.writeString(path, script);
			} catch (IOException cause) {
				throw new RuntimeException("Cannot write script", cause);
			}

			LOGGER.accept("Generate SVG");
			SourceStringReader reader = new SourceStringReader(script);
			FileOutputStream graphStream;
			try {
				graphStream = new FileOutputStream(svgPath.toFile());
			} catch (FileNotFoundException cause) {
				throw new RuntimeException("Cannot find: " + svgPath, cause);
			}
			String desc;
			try {
				desc = reader.outputImage(graphStream, new FileFormatOption(FileFormat.SVG)).getDescription();
			} catch (IOException cause) {
				throw new RuntimeException("Cannot write SVG", cause);
			}
			LOGGER.accept(desc);

			LOGGER.accept("Done");
		}

	}

	static RefinerIdSerializer createRefinerIdSerializer(Source.Refiner<Repository<MailId, Mail>, MailId, Mail> mailRefiner) {
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

	public static Repository.Updatable<IssueId, Issue> createIssueRepository(Path repositoryPath, Serializer<Issue, String> issueSerializer) {
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

	public static Repository.Updatable<QuestionId, Question> createQuestionRepository(Path repositoryPath, Serializer<Question, String> questionSerializer) {
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
				.flatMap(Main2::flattenRecursively)//
				.filter(body -> {
					return body.mimeType().equals(MimeType.Text.PLAIN) //
							|| body.mimeType().equals(MimeType.Text.HTML);
				}).findFirst().orElseThrow();
	}

	public static Stream<? extends Body> flattenRecursively(Body body) {
		return body instanceof Mail.Body.Composed composed //
				? composed.bodies().stream().flatMap(Main2::flattenRecursively) //
				: Stream.of(body);
	}

	private static interface Graph<V, E> {
		Stream<V> vertices();

		Stream<E> edges();

		static class PartialModel implements Graph<PartialModel.ID, PartialModel.Relation> {
			private final List<String> resourceKeys;
			private final Collection<ID> ids = new LinkedHashSet<>();
			private final List<Relation> relations = new LinkedList<>();

			PartialModel(List<String> resourceKeys) {
				this.resourceKeys = requireNonNull(resourceKeys);
			}

			static Calculation resource(Calculation.Mode mode, String resourceKey, double value) {
				requireNonNull(mode);
				requireNonNull(resourceKey);
				return new Calculation() {
					@Override
					public Graph.Instance.Resources compute(Graph.Instance.Node source) {
						requireNonNull(source);
						double ref = source.get(resourceKey).orElseThrow(() -> new IllegalArgumentException("No " + resourceKey + " in " + source.id()));
						double ratio = value / ref;
						return new Graph.Instance.Resources() {
							@Override
							public Number value() {
								return value;
							}

							@Override
							public Mode mode() {
								return mode;
							}

							@Override
							public double ratio() {
								return ratio;
							}
						};
					}
				};
			}

			static Calculation everything() {
				return ratio(1.0);
			}

			static Calculation ratio(double ratio) {
				return new Calculation() {
					@Override
					public Graph.Instance.Resources compute(Graph.Instance.Node source) {
						return new Graph.Instance.Resources() {
							@Override
							public Number value() {
								return ratio;
							}

							@Override
							public Mode mode() {
								return Mode.RATIO;
							}

							@Override
							public double ratio() {
								return ratio;
							}
						};
					}
				};
			}

			static Calculation tantiemes(int tantiemes) {
				return new Calculation() {
					@Override
					public Graph.Instance.Resources compute(Graph.Instance.Node source) {
						double ratio = (double) tantiemes / 10000;
						return new Graph.Instance.Resources() {
							@Override
							public Number value() {
								return tantiemes;
							}

							@Override
							public Mode mode() {
								return Mode.TANTIEMES;
							}

							@Override
							public double ratio() {
								return ratio;
							}
						};
					}
				};
			}

			static interface Group {
				Calculation part(double value);
			}

			Group createGroup() {
				var total = new Object() {
					BigDecimal value = BigDecimal.ZERO;
				};
				return new Group() {
					@Override
					public Calculation part(double value) {
						BigDecimal bigValue = BigDecimal.valueOf(value);
						total.value = total.value.add(bigValue);
						return new Calculation() {
							@Override
							public Graph.Instance.Resources compute(Graph.Instance.Node source) {
								if (total.value.compareTo(BigDecimal.ZERO) <= 0) {
									throw new IllegalArgumentException("No amount in " + this);
								}
								double ratio = bigValue.divide(total.value).doubleValue();
								return new Graph.Instance.Resources() {
									@Override
									public Number value() {
										return value;
									}

									@Override
									public Mode mode() {
										return Mode.SET;
									}

									@Override
									public double ratio() {
										return ratio;
									}
								};
							}
						};
					}
				};
			}

			public Calculation.Value variable(String string) {
				// TODO Auto-generated method stub
				return null;
			}

			static record ID(String value) {
				ID {
					requireNonNull(value);
				}
			}

			static interface Calculation {
				// TODO Make it consume Resources
				Graph.Instance.Resources compute(Graph.Instance.Node source);

				public interface Value {
					double resolve();
				}

				static class Mode {
					public static Mode RATIO = new Mode();
					public static Mode SET = new Mode();
					public static Mode MWH = new Mode();
					public static Mode WATER = new Mode();
					public static Mode TANTIEMES = new Mode();
				}

				@Deprecated
				static Calculation fromAll() {
					return fromRatio(1.0);
				}

				@Deprecated
				static Calculation fromRatio(double ratio) {
					return new Calculation() {
						@Override
						public Graph.Instance.Resources compute(Graph.Instance.Node source) {
							return new Graph.Instance.Resources() {
								@Override
								public Number value() {
									return ratio;
								}

								@Override
								public Mode mode() {
									return Mode.RATIO;
								}

								@Override
								public double ratio() {
									return ratio;
								}
							};
						}
					};
				}
			}

			public record Relation(ID source, Calculation calculation, ID target) {
				public Relation {
					requireNonNull(source);
					requireNonNull(calculation);
					requireNonNull(target);
				}
			}

			static interface Dispatcher {
				Dispatched to(String targetId);
			}

			static interface Dispatched {
				void taking(Calculation calculation);
			}

			Dispatcher dispatch(String sourceId) {
				requireNonNull(sourceId);
				return targetId -> {
					requireNonNull(targetId);
					return calculation -> {
						requireNonNull(calculation);
						relations.stream()//
								.map(relation -> Set.of(relation.source().value(), relation.target().value()))//
								.filter(set -> set.contains(sourceId) && set.contains(targetId))//
								.findFirst().ifPresent(set -> {
									throw new IllegalArgumentException(set + " are already linked");
								});

						ID source = new ID(sourceId);
						ID target = new ID(targetId);
						ids.add(source);
						ids.add(target);
						relations.add(new Relation(source, calculation, target));
					};
				};
			}

			@Override
			public Stream<ID> vertices() {
				return ids.stream();
			}

			@Override
			public Stream<Relation> edges() {
				return relations.stream();
			}

			Graph.CompleteModel complete() {
				return new CompleteModel(new LinkedList<>(ids), new LinkedList<>(relations), resourceKeys);
			}
		}

		static class CompleteModel implements Graph<PartialModel.ID, PartialModel.Relation> {
			private final Collection<PartialModel.ID> ids;
			private final List<PartialModel.Relation> relations;
			private final List<String> resourceKeys;
			private final Map<PartialModel.ID, Map<String, Double>> inputs = new HashMap<>();

			public CompleteModel(Collection<PartialModel.ID> ids, List<PartialModel.Relation> relations, List<String> resourceKeys) {
				this.ids = ids;
				this.relations = relations;
				this.resourceKeys = resourceKeys;
			}

			// TODO Factor
			@Deprecated
			public PartialModel.Calculation calculateFromAll() {
				return Graph.PartialModel.Calculation.fromAll();
			}

			@Override
			public Stream<PartialModel.ID> vertices() {
				return ids.stream();
			}

			@Override
			public Stream<PartialModel.Relation> edges() {
				return relations.stream();
			}

			void assign(String source, String key, double value) {
				requireNonNull(source);
				requireNonNull(key);

				PartialModel.ID id = new PartialModel.ID(source);
				ids.add(id);
				inputs.compute(id, (k, map) -> {
					if (map == null) {
						map = new HashMap<>();
					}
					map.compute(key, (k2, oldValue) -> {
						if (oldValue == null) {
							return value;
						} else {
							throw new IllegalArgumentException(source + " already has " + key + " with " + oldValue + ", cannot assign " + value);
						}
					});
					return map;
				});
			}

			void link(String sourceId, Graph.PartialModel.Calculation calculation, String targetId) {
				requireNonNull(sourceId);
				requireNonNull(calculation);
				requireNonNull(targetId);
				relations.stream()//
						.map(relation -> Set.of(relation.source().value(), relation.target().value()))//
						.filter(set -> set.contains(sourceId) && set.contains(targetId))//
						.findFirst().ifPresent(set -> {
							throw new IllegalArgumentException(set + " are already linked");
						});

				PartialModel.ID source = new PartialModel.ID(sourceId);
				PartialModel.ID target = new PartialModel.ID(targetId);
				ids.add(source);
				ids.add(target);
				relations.add(new PartialModel.Relation(source, calculation, target));
			}

			Graph.Instance instantiate() {
				Map<PartialModel.ID, Instance.Node> nodes = new HashMap<>();

				Collection<PartialModel.ID> inputIds = inputs.keySet();
				inputIds.stream().forEach(id -> {
					requireNonNull(id);
					Map<String, Double> values = inputs.get(id);
					requireNonNull(values);
					nodes.put(id, Instance.Node.create(id, resourceKey -> {
						requireNonNull(resourceKey);
						return Optional.ofNullable(values.get(resourceKey));
					}));
				});

				Comparator<PartialModel.ID> idComparator = idComparatorAsPerRelations(relations);

				ids.stream()//
						.sorted(idComparator)//
						.filter(id -> !inputIds.contains(id))//
						.forEach(targetId -> {
							Supplier<Map<String, Optional<BigDecimal>>> proxy = cache(() -> {
								Map<String, Optional<BigDecimal>> resourceValues = resourceKeys.stream().collect(Collectors.toMap(key -> key, key -> Optional.empty()));
								relations.stream()//
										.filter(relation -> relation.target().equals(targetId))//
										.forEach(relation -> {
											PartialModel.ID sourceId = relation.source();
											// TODO Get source resources
											// TODO Compute relation-specific target resources
											// TODO Add to overall target resources
											// TODO Build target from resources
											// TODO Focus on resources, build nodes at the end
											Instance.Node source = nodes.get(sourceId);
											Graph.Instance.Resources result = relation.calculation().compute(source);
											BigDecimal ratio = BigDecimal.valueOf(result.ratio());
											resourceKeys.forEach(resourceKey -> {
												resourceValues.compute(resourceKey, (k, currentValue) -> {
													return source.get(resourceKey)//
															.map(sourceValue -> BigDecimal.valueOf(sourceValue).multiply(ratio))//
															.map(valueToAdd -> currentValue.map(value -> value.add(valueToAdd)).orElse(valueToAdd))//
															.or(() -> currentValue);
												});
											});
										});
								return resourceValues;
							});
							nodes.put(targetId, Graph.Instance.Node.create(targetId, resourceKey -> proxy.get().get(resourceKey).map(BigDecimal::doubleValue)));
						});

				Collection<Instance.Link> links = new LinkedList<>();
				relations.stream()//
						.map(PartialModel.Relation::target)//
						.distinct()//
						.filter(id -> !inputIds.contains(id))//
						.forEach(targetId -> {
							requireNonNull(targetId);
							links.addAll(relations.stream()//
									.filter(relation -> relation.target().equals(targetId))//
									.map(relation -> {
										PartialModel.ID id = relation.source();
										Instance.Node source = nodes.get(id);
										requireNonNull(source, "No node for " + id);
										// TODO Store calculation instead of resources?
										Graph.Instance.Resources resources = relation.calculation().compute(source);
										return new Instance.Link(source, resources, nodes.get(targetId));
									}).toList());
						});

				return new Instance(nodes.values(), links);
			}

			private Comparator<PartialModel.ID> idComparatorAsPerRelations(List<PartialModel.Relation> relations) {
				Map<PartialModel.ID, Set<PartialModel.ID>> orderedIds = relations.stream().collect(Collectors.groupingBy(PartialModel.Relation::source, Collectors.mapping(PartialModel.Relation::target, Collectors.toSet())));
				BiPredicate<PartialModel.ID, PartialModel.ID> isBefore = (id1, id2) -> {
					Set<PartialModel.ID> ids = Set.of(id1);
					while (true) {
						Set<PartialModel.ID> nexts = ids.stream().map(orderedIds::get).filter(not(Objects::isNull)).reduce(new HashSet<>(), (s1, s2) -> {
							s1.addAll(s2);
							return s1;
						});
						if (nexts.isEmpty()) {
							return false;
						}
						if (nexts.contains(id2)) {
							return true;
						}
						ids = nexts;
					}
				};
				Comparator<PartialModel.ID> idValueComparator = Comparator.comparing(PartialModel.ID::value);
				Comparator<PartialModel.ID> idComparator = (id1, id2) -> {
					if (isBefore.test(id1, id2)) {
						return -1;
					} else if (isBefore.test(id2, id1)) {
						return 1;
					} else {
						return idValueComparator.compare(id1, id2);
					}
				};
				return idComparator;
			}
		}

		static class Instance implements Graph<Instance.Node, Instance.Link> {

			private final Collection<Node> nodes;
			private final Collection<Link> links;

			public Instance(Collection<Node> nodes, Collection<Link> links) {
				this.nodes = nodes;
				this.links = links;
			}

			@Override
			public Stream<Node> vertices() {
				return nodes.stream();
			}

			@Override
			public Stream<Link> edges() {
				return links.stream();
			}

			static interface Node {
				PartialModel.ID id();

				Optional<Double> get(String resourceKey);

				static Node create(PartialModel.ID id, Function<String, Optional<Double>> resourceProvider) {
					return new Node() {
						@Override
						public PartialModel.ID id() {
							return id;
						}

						@Override
						public Optional<Double> get(String resourceKey) {
							return resourceProvider.apply(resourceKey);
						}
					};
				}

			}

			// TODO Provide all resources
			static interface Resources {
				// TODO Move to renderer as graph decorator
				PartialModel.Calculation.Mode mode();

				// TODO Move to renderer as graph decorator
				Number value();

				// TODO Move it to Calculation
				double ratio();
			}

			static record Link(Node source, Resources result, Node target) {
				public Link {
					requireNonNull(source);
					requireNonNull(result);
					requireNonNull(target);
				}
			}
		}
	}

	private static <T> Supplier<T> cache(Supplier<T> supplier) {
		requireNonNull(supplier);
		return new Supplier<T>() {
			private T value = null;

			@Override
			public T get() {
				if (value == null) {
					value = supplier.get();
				}
				return value;
			}
		};
	}
}
