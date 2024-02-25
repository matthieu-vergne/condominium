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

			Graph graph = new Graph(resourceKeys);

			/* OUTPUT */

			String lot32 = "Lot.32";
			String lot33 = "Lot.33";

			/* STATIC SOURCE & STATIC INFO */

			// TODO Retrieve lots tantiemes from CSV(Lots, PCg/s)
			String tantièmesPcs3 = "Tantiemes.PCS3";
			graph.link(tantièmesPcs3, Graph.Calculation.fromTantiemes(317), lot32);
			graph.link(tantièmesPcs3, Graph.Calculation.fromTantiemes(449), lot33);

			String tantièmesPcs4 = "Tantiemes.PCS4";
			graph.link(tantièmesPcs4, Graph.Calculation.fromTantiemes(347), lot32);
			graph.link(tantièmesPcs4, Graph.Calculation.fromTantiemes(494), lot33);

			String tantièmesChauffage = "Tantiemes.ECS_Chauffage";
			graph.link(tantièmesChauffage, Graph.Calculation.fromTantiemes(127), lot32);
			graph.link(tantièmesChauffage, Graph.Calculation.fromTantiemes(179), lot33);

			String tantièmesRafraichissement = "Tantiemes.Rafraichissement";
			graph.link(tantièmesRafraichissement, Graph.Calculation.fromTantiemes(182), lot32);
			graph.link(tantièmesRafraichissement, Graph.Calculation.fromTantiemes(256), lot33);

			String elecChaufferieCombustibleECSTantiemes = "Elec.Chaufferie.combustibleECSTantiemes";
			graph.link(elecChaufferieCombustibleECSTantiemes, Graph.Calculation.fromAll(), tantièmesChauffage);
			String elecChaufferieCombustibleECSCompteurs = "Elec.Chaufferie.combustibleECSCompteurs";
			String elecChaufferieCombustibleRCTantiemes = "Elec.Chaufferie.combustibleRCTantiemes";
			graph.link(elecChaufferieCombustibleRCTantiemes, Graph.Calculation.fromRatio(0.5), tantièmesChauffage);
			graph.link(elecChaufferieCombustibleRCTantiemes, Graph.Calculation.fromRatio(0.5), tantièmesRafraichissement);
			String elecChaufferieCombustibleRCCompteurs = "Elec.Chaufferie.combustibleRCCompteurs";
			String elecChaufferieAutreTantiemes = "Elec.Chaufferie.autreTantiemes";
			graph.link(elecChaufferieAutreTantiemes, Graph.Calculation.fromRatio(0.5), tantièmesChauffage);
			graph.link(elecChaufferieAutreTantiemes, Graph.Calculation.fromRatio(0.5), tantièmesRafraichissement);
			String elecChaufferieAutreMesures = "Elec.Chaufferie.autreMesures";

			/* STATIC SOURCE & DYNAMIC INFO */

			String eauPotableFroideLot32 = "Eau.Potable.Froide.lot32";
			graph.link(eauPotableFroideLot32, Graph.Calculation.fromAll(), lot32);
			String eauPotableFroideLot33 = "Eau.Potable.Froide.lot33";
			graph.link(eauPotableFroideLot33, Graph.Calculation.fromAll(), lot33);

			Graph.Calculation.SetX setECS = Graph.Calculation.createSet();
			String eauPotableChaudeLot32 = "Eau.Potable.Chaude.lot32";
			graph.link(eauPotableChaudeLot32, Graph.Calculation.fromAll(), lot32);
			double ecs32 = 10.0;
			Graph.Calculation ecs32b = Graph.Calculation.fromSet(ecs32, setECS);
			graph.link(elecChaufferieCombustibleECSCompteurs, ecs32b, eauPotableChaudeLot32);// TODO Dispatch up
			String eauPotableChaudeLot33 = "Eau.Potable.Chaude.lot33";
			graph.link(eauPotableChaudeLot33, Graph.Calculation.fromAll(), lot33);
			double ecs33 = 10.0;
			Graph.Calculation ecs33b = Graph.Calculation.fromSet(ecs33, setECS);
			graph.link(elecChaufferieCombustibleECSCompteurs, ecs33b, eauPotableChaudeLot33);// TODO Dispatch up
			setECS.release();

			Graph.Calculation.SetX setCalorifique = Graph.Calculation.createSet();
			String elecCalorifiqueLot32 = "Elec.Calorifique.lot32";
			graph.link(elecCalorifiqueLot32, Graph.Calculation.fromAll(), lot32);
			Graph.Calculation calorifique32 = Graph.Calculation.fromSet(0.1, setCalorifique);
			graph.link(elecChaufferieCombustibleRCCompteurs, calorifique32, elecCalorifiqueLot32);
			graph.link(elecChaufferieAutreMesures, calorifique32, elecCalorifiqueLot32);
			String elecCalorifiqueLot33 = "Elec.Calorifique.lot33";
			graph.link(elecCalorifiqueLot33, Graph.Calculation.fromAll(), lot33);
			Graph.Calculation calorifique33 = Graph.Calculation.fromSet(0.1, setCalorifique);
			graph.link(elecChaufferieCombustibleRCCompteurs, calorifique33, elecCalorifiqueLot33);
			graph.link(elecChaufferieAutreMesures, calorifique33, elecCalorifiqueLot33);
			setCalorifique.release();

			String eauPotableChaufferie = "Eau.Potable.chaufferie";
			graph.link(eauPotableChaufferie, Graph.Calculation.fromResource(Graph.Mode.WATER, waterKey, ecs32), eauPotableChaudeLot32);
			graph.link(eauPotableChaufferie, Graph.Calculation.fromResource(Graph.Mode.WATER, waterKey, ecs33), eauPotableChaudeLot33);
			String eauPotableGeneral = "Eau.Potable.general";
			graph.link(eauPotableGeneral, Graph.Calculation.fromResource(Graph.Mode.WATER, waterKey, 50.0), eauPotableChaufferie);
			graph.link(eauPotableGeneral, Graph.Calculation.fromResource(Graph.Mode.WATER, waterKey, 0.1), eauPotableFroideLot32);
			graph.link(eauPotableGeneral, Graph.Calculation.fromResource(Graph.Mode.WATER, waterKey, 0.1), eauPotableFroideLot33);

			String elecChaufferieAutre = "Elec.Chaufferie.autre";
			graph.link(elecChaufferieAutre, Graph.Calculation.fromRatio(0.5), elecChaufferieAutreMesures);
			graph.link(elecChaufferieAutre, Graph.Calculation.fromRatio(0.5), elecChaufferieAutreTantiemes);
			String elecChaufferieCombustibleRC = "Elec.Chaufferie.combustibleRC";
			graph.link(elecChaufferieCombustibleRC, Graph.Calculation.fromRatio(0.3), elecChaufferieCombustibleRCTantiemes);
			graph.link(elecChaufferieCombustibleRC, Graph.Calculation.fromRatio(0.7), elecChaufferieCombustibleRCCompteurs);
			String elecChaufferieCombustibleECS = "Elec.Chaufferie.combustibleECS";
			graph.link(elecChaufferieCombustibleECS, Graph.Calculation.fromRatio(0.3), elecChaufferieCombustibleECSTantiemes);
			graph.link(elecChaufferieCombustibleECS, Graph.Calculation.fromRatio(0.7), elecChaufferieCombustibleECSCompteurs);
			String elecChaufferieCombustible = "Elec.Chaufferie.combustible";
			graph.link(elecChaufferieCombustible, Graph.Calculation.fromResource(Graph.Mode.MWH, mwhKey, 15.0), elecChaufferieCombustibleECS);
			graph.link(elecChaufferieCombustible, Graph.Calculation.fromResource(Graph.Mode.MWH, mwhKey, 15.0), elecChaufferieCombustibleRC);
			String elecChaufferie = "Elec.Chaufferie.general";
			graph.link(elecChaufferie, Graph.Calculation.fromResource(Graph.Mode.MWH, mwhKey, 30.0), elecChaufferieCombustible);
			graph.link(elecChaufferie, Graph.Calculation.fromResource(Graph.Mode.MWH, mwhKey, 20.0), elecChaufferieAutre);
			String elecTgbtAscenseurBoussole = "Elec.TGBT.ascenseur_boussole";
			graph.link(elecTgbtAscenseurBoussole, Graph.Calculation.fromAll(), tantièmesPcs3);
			String elecTgbtGeneral = "Elec.TGBT.general";
			graph.link(elecTgbtGeneral, Graph.Calculation.fromResource(Graph.Mode.MWH, mwhKey, 10.0), elecTgbtAscenseurBoussole);
			graph.link(elecTgbtGeneral, Graph.Calculation.fromResource(Graph.Mode.MWH, mwhKey, 50.0), elecChaufferie);

			/* DYNAMIC SOURCE & DYNAMIC INFO */

			String factureElec = "Facture.Elec";
			graph.assign(factureElec, mwhKey, 100.0);
			graph.assign(factureElec, eurosKey, 1000.0);
			graph.link(factureElec, Graph.Calculation.fromResource(Graph.Mode.MWH, mwhKey, 100.0), elecTgbtGeneral);

			String factureWater = "Facture.Eau";
			graph.assign(factureWater, waterKey, 100.0);
			graph.assign(factureWater, eurosKey, 1000.0);
			graph.link(factureWater, Graph.Calculation.fromResource(Graph.Mode.WATER, waterKey, 100.0), eauPotableGeneral);

			String facturePoubellesBoussole = "Facture.PoubelleBoussole";
			graph.assign(facturePoubellesBoussole, eurosKey, 100.0);
			graph.link(facturePoubellesBoussole, Graph.Calculation.fromAll(), tantièmesPcs4);

			graph.compute();

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
			Comparator<Graph.ID> idComparator = comparing(Graph.ID::value);
			Comparator<Graph.Node> nodeComaprator = comparing(Graph.Node::id, idComparator);
			graph.nodes().stream()//
					// TODO Remove sort once tested
					.sorted(nodeComaprator)//
					.forEach(node -> {
						scriptStream.println("map " + node.id().value() + " {");
						resourceKeys.forEach(resourceKey -> {
							scriptStream.println("	" + resourceRenderer.get(resourceKey) + " => " + node.get(resourceKey).map(Object::toString).orElse(""));
						});
						scriptStream.println("}");
					});
			graph.links().stream()//
					// TODO Remove sort once tested
					.sorted(comparing(Graph.Link::source, nodeComaprator).thenComparing(Graph.Link::target, nodeComaprator))//
					.forEach(link -> {
						Graph.Calculation calculation = link.calculation();
						Graph.Mode mode = calculation.mode();
						String calculationString;
						if (mode == Graph.Mode.RATIO) {
							calculationString = ((double) calculation.value() * 100) + " %";
						} else if (mode == Graph.Mode.TANTIEMES) {
							calculationString = (int) calculation.value() + " t";
						} else if (mode == Graph.Mode.MWH) {
							calculationString = (double) calculation.value() + " " + resourceRenderer.get(mwhKey);
						} else if (mode == Graph.Mode.WATER) {
							calculationString = (double) calculation.value() + " " + resourceRenderer.get(waterKey);
						} else if (mode == Graph.Mode.SET) {
							calculationString = (double) calculation.value() + " from set";
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

	private static class Graph {
		private final List<String> resourceKeys;
		private final Collection<Graph.ID> ids = new LinkedHashSet<>();
		private final List<Relation> relations = new LinkedList<>();
		private final Map<Graph.ID, Map<String, Double>> inputs = new HashMap<>();

		Graph(List<String> resourceKeys) {
			this.resourceKeys = requireNonNull(resourceKeys);
		}

		void assign(String source, String key, double value) {
			requireNonNull(source);
			requireNonNull(key);

			ID id = new ID(source);
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

		private record Relation(ID source, Calculation calculation, ID target) {
			public Relation {
				requireNonNull(source);
				requireNonNull(calculation);
				requireNonNull(target);
			}
		}

		void link(String sourceId, Calculation calculation, String targetId) {
			requireNonNull(sourceId);
			requireNonNull(calculation);
			requireNonNull(targetId);
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
		}

		private Map<Graph.ID, Node> nodes;
		private Collection<Link> links;

		void compute() {
			nodes = new HashMap<>();

			Collection<ID> inputIds = inputs.keySet();
			inputIds.stream().forEach(id -> {
				requireNonNull(id);
				Map<String, Double> values = inputs.get(id);
				requireNonNull(values);
				nodes.put(id, Node.create(id, resourceKey -> {
					requireNonNull(resourceKey);
					return Optional.ofNullable(values.get(resourceKey));
				}));
			});

			Comparator<ID> idComparator = idComparatorAsPerRelations(relations);

			ids.stream()//
					.sorted(idComparator)//
					.filter(id -> !inputIds.contains(id))//
					.forEach(targetId -> {
						Supplier<Map<String, Optional<BigDecimal>>> proxy = cache(() -> {
							Map<String, Optional<BigDecimal>> resourceValues = resourceKeys.stream().collect(Collectors.toMap(key -> key, key -> Optional.empty()));
							relations.stream()//
									.filter(relation -> relation.target().equals(targetId))//
									.forEach(relation -> {
										Graph.ID sourceId = relation.source();
										Node source = nodes.get(sourceId);
										Graph.Calculation calculation = relation.calculation();
										BigDecimal ratio = BigDecimal.valueOf(calculation.ratio(source));
										resourceKeys.forEach(resourceKey -> {
											resourceValues.compute(resourceKey, (k, result) -> {
												return source.get(resourceKey)//
														.map(sourceValue -> BigDecimal.valueOf(sourceValue).multiply(ratio))//
														.map(valueToAdd -> result.map(value -> value.add(valueToAdd)).orElse(valueToAdd))//
														.or(() -> result);
											});
										});
									});
							return resourceValues;
						});
						nodes.put(targetId, Graph.Node.create(targetId, resourceKey -> proxy.get().get(resourceKey).map(BigDecimal::doubleValue)));
					});

			relations.sort(comparing(Relation::source, idComparator).thenComparing(Relation::target, idComparator));

			links = new LinkedList<>();
			relations.stream()//
					.map(Relation::target)//
					.distinct()//
					.filter(id -> !inputIds.contains(id))//
					.forEach(targetId -> {
						requireNonNull(targetId);
						links.addAll(relations.stream()//
								.filter(relation -> relation.target().equals(targetId))//
								.map(relation -> {
									ID id = relation.source();
									Node source = nodes.get(id);
									requireNonNull(source, "No node for " + id);
									Calculation calculation = relation.calculation();
									return new Link(source, calculation, nodes.get(targetId));
								}).toList());
					});
		}

		private Comparator<ID> idComparatorAsPerRelations(List<Relation> relations) {
			Map<ID, Set<ID>> orderedIds = relations.stream().collect(Collectors.groupingBy(Relation::source, Collectors.mapping(Relation::target, Collectors.toSet())));
			BiPredicate<ID, ID> isBefore = (id1, id2) -> {
				Set<ID> ids = Set.of(id1);
				while (true) {
					Set<ID> nexts = ids.stream().map(orderedIds::get).filter(not(Objects::isNull)).reduce(new HashSet<>(), (s1, s2) -> {
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
			Comparator<ID> idValueComparator = Comparator.comparing(ID::value);
			Comparator<ID> idComparator = (id1, id2) -> {
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

		Collection<Node> nodes() {
			return nodes.values();
		}

		Collection<Link> links() {
			return links;
		}

		private static interface Node {
			ID id();

			Optional<Double> get(String resourceKey);

			static Node create(ID id, Function<String, Optional<Double>> resourceProvider) {
				return new Node() {
					@Override
					public ID id() {
						return id;
					}

					@Override
					public Optional<Double> get(String resourceKey) {
						return resourceProvider.apply(resourceKey);
					}
				};
			}

		}

		static record ID(String value) {
			ID {
				requireNonNull(value);
			}
		}

		static class Mode {
			public static Mode RATIO = new Mode();
			public static Mode SET = new Mode();
			public static Mode MWH = new Mode();
			public static Mode WATER = new Mode();
			public static Mode TANTIEMES = new Mode();
		}

		static interface Calculation {

			Number value();

			Mode mode();

			double ratio(Node source);

			static interface SetX {
				void register(Calculation calculation);

				Stream<Calculation> stream();

				void release();
			}

			static Calculation fromAll() {
				return fromRatio(1.0);
			}

			static Calculation fromRatio(double ratio) {
				return new Calculation() {

					@Override
					public Number value() {
						return ratio;
					}

					@Override
					public double ratio(Node source) {
						return ratio;
					}

					@Override
					public Mode mode() {
						return Mode.RATIO;
					}
				};
			}

			static Calculation fromResource(Mode mode, String resourceKey, double value) {
				requireNonNull(mode);
				requireNonNull(resourceKey);
				return new Calculation() {

					@Override
					public Number value() {
						return value;
					}

					@Override
					public double ratio(Node source) {
						requireNonNull(source);
						double ref = source.get(resourceKey).orElseThrow(() -> new IllegalArgumentException("No " + resourceKey + " in " + source.id()));
						return value / ref;
					}

					@Override
					public Mode mode() {
						return mode;
					}
				};
			}

			static Calculation fromSet(double value, SetX set) {
				requireNonNull(set);
				Calculation calculation = new Calculation() {

					@Override
					public Number value() {
						return value;
					}

					@Override
					public double ratio(Node source) {
						requireNonNull(source);
						double ref = set.stream()//
								.map(Graph.Calculation::value)//
								.map(Number::doubleValue)//
								.map(BigDecimal::valueOf)//
								.reduce(BigDecimal::add)//
								.orElseThrow()//
								.doubleValue();
						if (ref <= 0) {
							throw new IllegalArgumentException("No amount in " + set);
						}
						return value / ref;
					}

					@Override
					public Mode mode() {
						return Mode.SET;
					}
				};
				set.register(calculation);
				return calculation;
			}

			static Calculation fromTantiemes(int tantiemes) {
				return new Calculation() {
					@Override
					public Number value() {
						return tantiemes;
					}

					@Override
					public double ratio(Node source) {
						return (double) tantiemes / 10000;
					}

					@Override
					public Mode mode() {
						return Mode.TANTIEMES;
					}
				};
			}

			static SetX createSet() {
				Set<Graph.Calculation> sources = new HashSet<>();
				return new SetX() {
					private boolean released = false;

					public void register(Graph.Calculation calculation) {
						requireNonNull(calculation);
						sources.add(calculation);
					};

					@Override
					public void release() {
						this.released = true;
					}

					@Override
					public Stream<Graph.Calculation> stream() {
						if (!released) {
							throw new IllegalStateException("Not relased yet");
						}
						return sources.stream();
					}
				};
			}
		}

		static record Link(Node source, Calculation calculation, Node target) {
			Link {
				requireNonNull(source);
				requireNonNull(calculation);
				requireNonNull(target);
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
