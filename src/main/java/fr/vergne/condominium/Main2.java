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

			Graph.Static graphStatic = new Graph.Static(resourceKeys);

			/* OUTPUT */

			String lot32 = "Lot.32";
			String lot33 = "Lot.33";

			/* STATIC SOURCE & STATIC INFO */

			// TODO Retrieve lots tantiemes from CSV(Lots, PCg/s)
			String tantièmesPcs3 = "Tantiemes.PCS3";
			graphStatic.link(tantièmesPcs3, Graph.Static.Calculation.fromTantiemes(317), lot32);
			graphStatic.link(tantièmesPcs3, Graph.Static.Calculation.fromTantiemes(449), lot33);

			String tantièmesPcs4 = "Tantiemes.PCS4";
			graphStatic.link(tantièmesPcs4, Graph.Static.Calculation.fromTantiemes(347), lot32);
			graphStatic.link(tantièmesPcs4, Graph.Static.Calculation.fromTantiemes(494), lot33);

			String tantièmesChauffage = "Tantiemes.ECS_Chauffage";
			graphStatic.link(tantièmesChauffage, Graph.Static.Calculation.fromTantiemes(127), lot32);
			graphStatic.link(tantièmesChauffage, Graph.Static.Calculation.fromTantiemes(179), lot33);

			String tantièmesRafraichissement = "Tantiemes.Rafraichissement";
			graphStatic.link(tantièmesRafraichissement, Graph.Static.Calculation.fromTantiemes(182), lot32);
			graphStatic.link(tantièmesRafraichissement, Graph.Static.Calculation.fromTantiemes(256), lot33);

			String elecChaufferieCombustibleECSTantiemes = "Elec.Chaufferie.combustibleECSTantiemes";
			graphStatic.link(elecChaufferieCombustibleECSTantiemes, Graph.Static.Calculation.fromAll(), tantièmesChauffage);
			String elecChaufferieCombustibleECSCompteurs = "Elec.Chaufferie.combustibleECSCompteurs";
			String elecChaufferieCombustibleRCTantiemes = "Elec.Chaufferie.combustibleRCTantiemes";
			graphStatic.link(elecChaufferieCombustibleRCTantiemes, Graph.Static.Calculation.fromRatio(0.5), tantièmesChauffage);
			graphStatic.link(elecChaufferieCombustibleRCTantiemes, Graph.Static.Calculation.fromRatio(0.5), tantièmesRafraichissement);
			String elecChaufferieCombustibleRCCompteurs = "Elec.Chaufferie.combustibleRCCompteurs";
			String elecChaufferieAutreTantiemes = "Elec.Chaufferie.autreTantiemes";
			graphStatic.link(elecChaufferieAutreTantiemes, Graph.Static.Calculation.fromRatio(0.5), tantièmesChauffage);
			graphStatic.link(elecChaufferieAutreTantiemes, Graph.Static.Calculation.fromRatio(0.5), tantièmesRafraichissement);
			String elecChaufferieAutreMesures = "Elec.Chaufferie.autreMesures";

			/* STATIC SOURCE & DYNAMIC INFO */

			String eauPotableFroideLot32 = "Eau.Potable.Froide.lot32";
			graphStatic.link(eauPotableFroideLot32, Graph.Static.Calculation.fromAll(), lot32);
			String eauPotableFroideLot33 = "Eau.Potable.Froide.lot33";
			graphStatic.link(eauPotableFroideLot33, Graph.Static.Calculation.fromAll(), lot33);

			Graph.Static.Calculation.SetX setECS = Graph.Static.Calculation.createSet();
			String eauPotableChaudeLot32 = "Eau.Potable.Chaude.lot32";
			graphStatic.link(eauPotableChaudeLot32, Graph.Static.Calculation.fromAll(), lot32);
			// TODO Introduce intermediary variables
			double ecs32 = 10.0;
			graphStatic.link(elecChaufferieCombustibleECSCompteurs, Graph.Static.Calculation.fromSet(ecs32, setECS), eauPotableChaudeLot32);// TODO Dispatch up
			String eauPotableChaudeLot33 = "Eau.Potable.Chaude.lot33";
			graphStatic.link(eauPotableChaudeLot33, Graph.Static.Calculation.fromAll(), lot33);
			double ecs33 = 10.0;
			graphStatic.link(elecChaufferieCombustibleECSCompteurs, Graph.Static.Calculation.fromSet(ecs33, setECS), eauPotableChaudeLot33);// TODO Dispatch up
			setECS.release();

			Graph.Static.Calculation.SetX setCalorifique = Graph.Static.Calculation.createSet();
			String elecCalorifiqueLot32 = "Elec.Calorifique.lot32";
			graphStatic.link(elecCalorifiqueLot32, Graph.Static.Calculation.fromAll(), lot32);
			Graph.Static.Calculation calorifique32 = Graph.Static.Calculation.fromSet(0.1, setCalorifique);
			graphStatic.link(elecChaufferieCombustibleRCCompteurs, calorifique32, elecCalorifiqueLot32);
			graphStatic.link(elecChaufferieAutreMesures, calorifique32, elecCalorifiqueLot32);
			String elecCalorifiqueLot33 = "Elec.Calorifique.lot33";
			graphStatic.link(elecCalorifiqueLot33, Graph.Static.Calculation.fromAll(), lot33);
			Graph.Static.Calculation calorifique33 = Graph.Static.Calculation.fromSet(0.1, setCalorifique);
			graphStatic.link(elecChaufferieCombustibleRCCompteurs, calorifique33, elecCalorifiqueLot33);
			graphStatic.link(elecChaufferieAutreMesures, calorifique33, elecCalorifiqueLot33);
			setCalorifique.release();

			String eauPotableChaufferie = "Eau.Potable.chaufferie";
			graphStatic.link(eauPotableChaufferie, Graph.Static.Calculation.fromResource(Graph.Static.Calculation.Mode.WATER, waterKey, ecs32), eauPotableChaudeLot32);
			graphStatic.link(eauPotableChaufferie, Graph.Static.Calculation.fromResource(Graph.Static.Calculation.Mode.WATER, waterKey, ecs33), eauPotableChaudeLot33);
			String eauPotableGeneral = "Eau.Potable.general";
			graphStatic.link(eauPotableGeneral, Graph.Static.Calculation.fromResource(Graph.Static.Calculation.Mode.WATER, waterKey, 50.0), eauPotableChaufferie);
			graphStatic.link(eauPotableGeneral, Graph.Static.Calculation.fromResource(Graph.Static.Calculation.Mode.WATER, waterKey, 0.1), eauPotableFroideLot32);
			graphStatic.link(eauPotableGeneral, Graph.Static.Calculation.fromResource(Graph.Static.Calculation.Mode.WATER, waterKey, 0.1), eauPotableFroideLot33);

			String elecChaufferieAutre = "Elec.Chaufferie.autre";
			graphStatic.link(elecChaufferieAutre, Graph.Static.Calculation.fromRatio(0.5), elecChaufferieAutreMesures);
			graphStatic.link(elecChaufferieAutre, Graph.Static.Calculation.fromRatio(0.5), elecChaufferieAutreTantiemes);
			String elecChaufferieCombustibleRC = "Elec.Chaufferie.combustibleRC";
			graphStatic.link(elecChaufferieCombustibleRC, Graph.Static.Calculation.fromRatio(0.3), elecChaufferieCombustibleRCTantiemes);
			graphStatic.link(elecChaufferieCombustibleRC, Graph.Static.Calculation.fromRatio(0.7), elecChaufferieCombustibleRCCompteurs);
			String elecChaufferieCombustibleECS = "Elec.Chaufferie.combustibleECS";
			graphStatic.link(elecChaufferieCombustibleECS, Graph.Static.Calculation.fromRatio(0.3), elecChaufferieCombustibleECSTantiemes);
			graphStatic.link(elecChaufferieCombustibleECS, Graph.Static.Calculation.fromRatio(0.7), elecChaufferieCombustibleECSCompteurs);
			String elecChaufferieCombustible = "Elec.Chaufferie.combustible";
			graphStatic.link(elecChaufferieCombustible, Graph.Static.Calculation.fromResource(Graph.Static.Calculation.Mode.MWH, mwhKey, 15.0), elecChaufferieCombustibleECS);
			graphStatic.link(elecChaufferieCombustible, Graph.Static.Calculation.fromResource(Graph.Static.Calculation.Mode.MWH, mwhKey, 15.0), elecChaufferieCombustibleRC);
			String elecChaufferie = "Elec.Chaufferie.general";
			graphStatic.link(elecChaufferie, Graph.Static.Calculation.fromResource(Graph.Static.Calculation.Mode.MWH, mwhKey, 30.0), elecChaufferieCombustible);
			graphStatic.link(elecChaufferie, Graph.Static.Calculation.fromResource(Graph.Static.Calculation.Mode.MWH, mwhKey, 20.0), elecChaufferieAutre);
			String elecTgbtAscenseurBoussole = "Elec.TGBT.ascenseur_boussole";
			graphStatic.link(elecTgbtAscenseurBoussole, Graph.Static.Calculation.fromAll(), tantièmesPcs3);
			String elecTgbtGeneral = "Elec.TGBT.general";
			graphStatic.link(elecTgbtGeneral, Graph.Static.Calculation.fromResource(Graph.Static.Calculation.Mode.MWH, mwhKey, 10.0), elecTgbtAscenseurBoussole);
			graphStatic.link(elecTgbtGeneral, Graph.Static.Calculation.fromResource(Graph.Static.Calculation.Mode.MWH, mwhKey, 50.0), elecChaufferie);

			/* DYNAMIC SOURCE & DYNAMIC INFO */

			Graph.Dynamic graphDynamic = graphStatic.specialize();

			String factureElec = "Facture.Elec";
			graphDynamic.assign(factureElec, mwhKey, 100.0);
			graphDynamic.assign(factureElec, eurosKey, 1000.0);
			graphDynamic.link(factureElec, Graph.Static.Calculation.fromResource(Graph.Static.Calculation.Mode.MWH, mwhKey, 100.0), elecTgbtGeneral);

			String factureWater = "Facture.Eau";
			graphDynamic.assign(factureWater, waterKey, 100.0);
			graphDynamic.assign(factureWater, eurosKey, 1000.0);
			graphDynamic.link(factureWater, Graph.Static.Calculation.fromResource(Graph.Static.Calculation.Mode.WATER, waterKey, 100.0), eauPotableGeneral);

			String facturePoubellesBoussole = "Facture.PoubelleBoussole";
			graphDynamic.assign(facturePoubellesBoussole, eurosKey, 100.0);
			graphDynamic.link(facturePoubellesBoussole, Graph.Static.Calculation.fromAll(), tantièmesPcs4);

			Graph.Instance graphInstance = graphDynamic.compute();

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
			Comparator<Graph.Static.ID> idComparator = comparing(Graph.Static.ID::value);
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
						Graph.Instance.Result result = link.result();
						Graph.Static.Calculation.Mode mode = result.mode();
						String calculationString;
						if (mode == Graph.Static.Calculation.Mode.RATIO) {
							calculationString = ((double) result.value() * 100) + " %";
						} else if (mode == Graph.Static.Calculation.Mode.TANTIEMES) {
							calculationString = (int) result.value() + " t";
						} else if (mode == Graph.Static.Calculation.Mode.MWH) {
							calculationString = (double) result.value() + " " + resourceRenderer.get(mwhKey);
						} else if (mode == Graph.Static.Calculation.Mode.WATER) {
							calculationString = (double) result.value() + " " + resourceRenderer.get(waterKey);
						} else if (mode == Graph.Static.Calculation.Mode.SET) {
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

		static class Static implements Graph<Static.ID, Static.Relation> {
			private final List<String> resourceKeys;
			private final Collection<ID> ids = new LinkedHashSet<>();
			private final List<Relation> relations = new LinkedList<>();

			Static(List<String> resourceKeys) {
				this.resourceKeys = requireNonNull(resourceKeys);
			}

			static record ID(String value) {
				ID {
					requireNonNull(value);
				}
			}

			static interface Calculation {

				Graph.Instance.Result compute(Graph.Instance.Node source);

				static class Mode {
					public static Mode RATIO = new Mode();
					public static Mode SET = new Mode();
					public static Mode MWH = new Mode();
					public static Mode WATER = new Mode();
					public static Mode TANTIEMES = new Mode();
				}

				static interface SetX {
					void register(Calculation calculation, double value);

					double value(Calculation calculation);

					Stream<Calculation> stream();

					void release();
				}

				static Calculation fromAll() {
					return fromRatio(1.0);
				}

				static Calculation fromRatio(double ratio) {
					return new Calculation() {
						@Override
						public Graph.Instance.Result compute(Graph.Instance.Node source) {
							return new Graph.Instance.Result() {
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

				static Calculation fromResource(Mode mode, String resourceKey, double value) {
					requireNonNull(mode);
					requireNonNull(resourceKey);
					return new Calculation() {
						@Override
						public Graph.Instance.Result compute(Graph.Instance.Node source) {
							requireNonNull(source);
							double ref = source.get(resourceKey).orElseThrow(() -> new IllegalArgumentException("No " + resourceKey + " in " + source.id()));
							double ratio = value / ref;
							return new Graph.Instance.Result() {
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

				static Calculation fromSet(double value, SetX set) {
					requireNonNull(set);
					Calculation calculation = new Calculation() {
						@Override
						public Graph.Instance.Result compute(Graph.Instance.Node source) {
							double ref = set.stream()//
									.map(set::value)//
									.map(BigDecimal::valueOf)//
									.reduce(BigDecimal::add)//
									.orElseThrow()//
									.doubleValue();
							if (ref <= 0) {
								throw new IllegalArgumentException("No amount in " + set);
							}
							double ratio = value / ref;

							return new Graph.Instance.Result() {
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
					set.register(calculation, value);
					return calculation;
				}

				static Calculation fromTantiemes(int tantiemes) {
					return new Calculation() {
						@Override
						public Graph.Instance.Result compute(Graph.Instance.Node source) {
							double ratio = (double) tantiemes / 10000;
							return new Graph.Instance.Result() {
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

				static SetX createSet() {
					Map<Graph.Static.Calculation, Double> sources = new HashMap<>();
					return new SetX() {
						private boolean released = false;

						@Override
						public void register(Calculation calculation, double value) {
							requireNonNull(calculation);
							sources.compute(calculation, (k, oldValue) -> {
								if (oldValue != null) {
									throw new IllegalArgumentException("Calculation already set with: " + oldValue);
								} else {
									return value;
								}
							});
						};

						@Override
						public double value(Calculation calculation) {
							return sources.get(calculation);
						}

						@Override
						public void release() {
							this.released = true;
						}

						@Override
						public Stream<Graph.Static.Calculation> stream() {
							if (!released) {
								throw new IllegalStateException("Not relased yet");
							}
							return sources.keySet().stream();
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

			@Override
			public Stream<ID> vertices() {
				return ids.stream();
			}

			@Override
			public Stream<Relation> edges() {
				return relations.stream();
			}

			Graph.Dynamic specialize() {
				return new Dynamic(new LinkedList<>(ids), new LinkedList<>(relations), resourceKeys);
			}
		}

		static class Dynamic implements Graph<Static.ID, Static.Relation> {
			private final Collection<Static.ID> ids;
			private final List<Static.Relation> relations;
			private final List<String> resourceKeys;
			private final Map<Static.ID, Map<String, Double>> inputs = new HashMap<>();

			public Dynamic(Collection<Static.ID> ids, List<Static.Relation> relations, List<String> resourceKeys) {
				this.ids = ids;
				this.relations = relations;
				this.resourceKeys = resourceKeys;
			}

			@Override
			public Stream<Static.ID> vertices() {
				return ids.stream();
			}

			@Override
			public Stream<Static.Relation> edges() {
				return relations.stream();
			}

			void assign(String source, String key, double value) {
				requireNonNull(source);
				requireNonNull(key);

				Static.ID id = new Static.ID(source);
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

			void link(String sourceId, Graph.Static.Calculation calculation, String targetId) {
				requireNonNull(sourceId);
				requireNonNull(calculation);
				requireNonNull(targetId);
				relations.stream()//
						.map(relation -> Set.of(relation.source().value(), relation.target().value()))//
						.filter(set -> set.contains(sourceId) && set.contains(targetId))//
						.findFirst().ifPresent(set -> {
							throw new IllegalArgumentException(set + " are already linked");
						});

				Static.ID source = new Static.ID(sourceId);
				Static.ID target = new Static.ID(targetId);
				ids.add(source);
				ids.add(target);
				relations.add(new Static.Relation(source, calculation, target));
			}

			Graph.Instance compute() {
				Map<Static.ID, Instance.Node> nodes = new HashMap<>();

				Collection<Static.ID> inputIds = inputs.keySet();
				inputIds.stream().forEach(id -> {
					requireNonNull(id);
					Map<String, Double> values = inputs.get(id);
					requireNonNull(values);
					nodes.put(id, Instance.Node.create(id, resourceKey -> {
						requireNonNull(resourceKey);
						return Optional.ofNullable(values.get(resourceKey));
					}));
				});

				Comparator<Static.ID> idComparator = idComparatorAsPerRelations(relations);

				Collection<Instance.Link> links = new LinkedList<>();
				ids.stream()//
						.sorted(idComparator)//
						.filter(id -> !inputIds.contains(id))//
						.forEach(targetId -> {
							Supplier<Map<String, Optional<BigDecimal>>> proxy = cache(() -> {
								Map<String, Optional<BigDecimal>> resourceValues = resourceKeys.stream().collect(Collectors.toMap(key -> key, key -> Optional.empty()));
								relations.stream()//
										.filter(relation -> relation.target().equals(targetId))//
										.forEach(relation -> {
											Static.ID sourceId = relation.source();
											Instance.Node source = nodes.get(sourceId);
											Graph.Instance.Result result = relation.calculation().compute(source);
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

				relations.stream()//
						.map(Static.Relation::target)//
						.distinct()//
						.filter(id -> !inputIds.contains(id))//
						.forEach(targetId -> {
							requireNonNull(targetId);
							links.addAll(relations.stream()//
									.filter(relation -> relation.target().equals(targetId))//
									.map(relation -> {
										Static.ID id = relation.source();
										Instance.Node source = nodes.get(id);
										requireNonNull(source, "No node for " + id);
										Graph.Instance.Result result = relation.calculation().compute(source);
										return new Instance.Link(source, result, nodes.get(targetId));
									}).toList());
						});

				return new Instance(nodes.values(), links);
			}

			private Comparator<Static.ID> idComparatorAsPerRelations(List<Static.Relation> relations) {
				Map<Static.ID, Set<Static.ID>> orderedIds = relations.stream().collect(Collectors.groupingBy(Static.Relation::source, Collectors.mapping(Static.Relation::target, Collectors.toSet())));
				BiPredicate<Static.ID, Static.ID> isBefore = (id1, id2) -> {
					Set<Static.ID> ids = Set.of(id1);
					while (true) {
						Set<Static.ID> nexts = ids.stream().map(orderedIds::get).filter(not(Objects::isNull)).reduce(new HashSet<>(), (s1, s2) -> {
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
				Comparator<Static.ID> idValueComparator = Comparator.comparing(Static.ID::value);
				Comparator<Static.ID> idComparator = (id1, id2) -> {
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
				Static.ID id();

				Optional<Double> get(String resourceKey);

				static Node create(Static.ID id, Function<String, Optional<Double>> resourceProvider) {
					return new Node() {
						@Override
						public Static.ID id() {
							return id;
						}

						@Override
						public Optional<Double> get(String resourceKey) {
							return resourceProvider.apply(resourceKey);
						}
					};
				}

			}

			static interface Result {
				Static.Calculation.Mode mode();

				Number value();

				double ratio();
			}

			static record Link(Node source, Result result, Node target) {
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
