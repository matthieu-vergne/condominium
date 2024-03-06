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
import net.sourceforge.plantuml.classdiagram.command.CommandCreateElementFull2.Mode;

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

			Calculation.Factory.Base baseCalculationFactory = new Calculation.Factory.Base();

			enum Mode {
				RATIO, SET, MWH, WATER, TANTIEMES
			}
			record EnrichedCalculation(Calculation delegate, Mode mode, Value<? extends Number> value) implements Calculation {
				@Override
				public Calculation.Resources compute(Calculation.Resources source) {
					return delegate.compute(source);
				}
			}
			var enrichedCalculationFactory = new Calculation.Factory() {

				private final Calculation.Factory factory = baseCalculationFactory;
				private final Map<String, Mode> resourceModes = Map.of(//
						mwhKey, Mode.MWH, //
						waterKey, Mode.WATER//
				);

				EnrichedCalculation enrich(Calculation calculation) {
					return (EnrichedCalculation) calculation;
				}

				@Override
				public Calculation resource(String resourceKey, Value<Double> value) {
					return new EnrichedCalculation(factory.resource(resourceKey, value), resourceModes.get(resourceKey), value);
				}

				@Override
				public Calculation everything() {
					return new EnrichedCalculation(factory.everything(), Mode.RATIO, () -> 1.0);
				}

				@Override
				public Calculation ratio(double ratio) {
					return new EnrichedCalculation(factory.ratio(ratio), Mode.RATIO, () -> ratio);
				}

				@Override
				public Calculation tantiemes(int tantiemes) {
					return new EnrichedCalculation(factory.tantiemes(tantiemes), Mode.TANTIEMES, () -> tantiemes);
				}

				@Override
				public Calculation.Factory.Group createGroup() {
					Calculation.Factory.Group group = factory.createGroup();
					return new Calculation.Factory.Group() {
						@Override
						public Calculation part(Value<Double> value) {
							return new EnrichedCalculation(group.part(value), Mode.SET, value);
						}
					};
				}
			};
			Calculation.Factory calc = enrichedCalculationFactory;
			Graph.Model partialModel = new Graph.Model();

			/* OUTPUT */
			Variables variables = new Variables();

			// TODO Retrieve lots from CSV
			String lot32 = "Lot.32";
			String lot33 = "Lot.33";

			/* STATIC SOURCE & STATIC INFO */
			String eauPotableFroideLot32 = "Eau.Potable.Froide.lot32";
			partialModel.dispatch(eauPotableFroideLot32).to(lot32).taking(calc.everything());
			String eau32Var = "eau32";
			Calculation eau32 = calc.resource(waterKey, variables.valueOf(eau32Var));

			String eauPotableFroideLot33 = "Eau.Potable.Froide.lot33";
			partialModel.dispatch(eauPotableFroideLot33).to(lot33).taking(calc.everything());
			String eau33Var = "eau33";
			Calculation eau33 = calc.resource(waterKey, variables.valueOf(eau33Var));

			Calculation.Factory.Group setECS = calc.createGroup();

			String eauPotableChaudeLot32 = "Eau.Potable.Chaude.lot32";
			partialModel.dispatch(eauPotableChaudeLot32).to(lot32).taking(calc.everything());
			String ecs32Var = "ecs32";
			Calculation ecs32 = setECS.part(variables.valueOf(ecs32Var));

			String eauPotableChaudeLot33 = "Eau.Potable.Chaude.lot33";
			partialModel.dispatch(eauPotableChaudeLot33).to(lot33).taking(calc.everything());
			String ecs33Var = "ecs33";
			Calculation ecs33 = setECS.part(variables.valueOf(ecs33Var));

			Calculation.Factory.Group setCal = calc.createGroup();

			String elecCalorifiqueLot32 = "Elec.Calorifique.lot32";
			partialModel.dispatch(elecCalorifiqueLot32).to(lot32).taking(calc.everything());
			String cal32Var = "cal32";
			Calculation cal32 = setCal.part(variables.valueOf(cal32Var));

			String elecCalorifiqueLot33 = "Elec.Calorifique.lot33";
			partialModel.dispatch(elecCalorifiqueLot33).to(lot33).taking(calc.everything());
			String cal33Var = "cal33";
			Calculation cal33 = setCal.part(variables.valueOf(cal33Var));

			// TODO Retrieve lots tantiemes from CSV
			String tantièmesPcs3 = "Tantiemes.PCS3";
			partialModel.dispatch(tantièmesPcs3).to(lot32).taking(calc.tantiemes(317));
			partialModel.dispatch(tantièmesPcs3).to(lot33).taking(calc.tantiemes(449));

			String tantièmesPcs4 = "Tantiemes.PCS4";
			partialModel.dispatch(tantièmesPcs4).to(lot32).taking(calc.tantiemes(347));
			partialModel.dispatch(tantièmesPcs4).to(lot33).taking(calc.tantiemes(494));

			String tantièmesChauffage = "Tantiemes.ECS_Chauffage";
			partialModel.dispatch(tantièmesChauffage).to(lot32).taking(calc.tantiemes(127));
			partialModel.dispatch(tantièmesChauffage).to(lot33).taking(calc.tantiemes(179));

			String tantièmesRafraichissement = "Tantiemes.Rafraichissement";
			partialModel.dispatch(tantièmesRafraichissement).to(lot32).taking(calc.tantiemes(182));
			partialModel.dispatch(tantièmesRafraichissement).to(lot33).taking(calc.tantiemes(256));

			// TODO Retrieve distribution from CSV
			String elecChaufferieCombustibleECSTantiemes = "Elec.Chaufferie.combustibleECSTantiemes";
			partialModel.dispatch(elecChaufferieCombustibleECSTantiemes).to(tantièmesChauffage).taking(calc.everything());

			String elecChaufferieCombustibleECSCompteurs = "Elec.Chaufferie.combustibleECSCompteurs";
			partialModel.dispatch(elecChaufferieCombustibleECSCompteurs).to(eauPotableChaudeLot32).taking(ecs32);
			partialModel.dispatch(elecChaufferieCombustibleECSCompteurs).to(eauPotableChaudeLot33).taking(ecs33);

			String elecChaufferieCombustibleRCTantiemes = "Elec.Chaufferie.combustibleRCTantiemes";
			partialModel.dispatch(elecChaufferieCombustibleRCTantiemes).to(tantièmesChauffage).taking(calc.ratio(0.5));
			partialModel.dispatch(elecChaufferieCombustibleRCTantiemes).to(tantièmesRafraichissement).taking(calc.ratio(0.5));

			String elecChaufferieCombustibleRCCompteurs = "Elec.Chaufferie.combustibleRCCompteurs";
			partialModel.dispatch(elecChaufferieCombustibleRCCompteurs).to(elecCalorifiqueLot32).taking(cal32);
			partialModel.dispatch(elecChaufferieCombustibleRCCompteurs).to(elecCalorifiqueLot33).taking(cal33);

			String elecChaufferieAutreTantiemes = "Elec.Chaufferie.autreTantiemes";
			partialModel.dispatch(elecChaufferieAutreTantiemes).to(tantièmesChauffage).taking(calc.ratio(0.5));
			partialModel.dispatch(elecChaufferieAutreTantiemes).to(tantièmesRafraichissement).taking(calc.ratio(0.5));

			String elecChaufferieAutreMesures = "Elec.Chaufferie.autreMesures";
			partialModel.dispatch(elecChaufferieAutreMesures).to(elecCalorifiqueLot32).taking(cal32);
			partialModel.dispatch(elecChaufferieAutreMesures).to(elecCalorifiqueLot33).taking(cal33);

			/* STATIC SOURCE & DYNAMIC INFO */

			String eauPotableChaufferie = "Eau.Potable.chaufferie";
			partialModel.dispatch(eauPotableChaufferie).to(eauPotableChaudeLot32).taking(calc.resource(waterKey, variables.valueOf(ecs32Var)));
			partialModel.dispatch(eauPotableChaufferie).to(eauPotableChaudeLot33).taking(calc.resource(waterKey, variables.valueOf(ecs33Var)));
			String eauChaufferieVar = "eauChaufferie";
			Calculation eauChaufferie = calc.resource(waterKey, variables.valueOf(eauChaufferieVar));

			String eauPotableGeneral = "Eau.Potable.general";
			partialModel.dispatch(eauPotableGeneral).to(eauPotableChaufferie).taking(eauChaufferie);
			partialModel.dispatch(eauPotableGeneral).to(eauPotableFroideLot32).taking(eau32);
			partialModel.dispatch(eauPotableGeneral).to(eauPotableFroideLot33).taking(eau33);

			String elecChaufferieAutre = "Elec.Chaufferie.autre";
			partialModel.dispatch(elecChaufferieAutre).to(elecChaufferieAutreMesures).taking(calc.ratio(0.5));
			partialModel.dispatch(elecChaufferieAutre).to(elecChaufferieAutreTantiemes).taking(calc.ratio(0.5));
			String mwhChaufferieAutreVar = "mwhChaufferieAutre";
			Calculation mwhChaufferieAutre = calc.resource(mwhKey, variables.valueOf(mwhChaufferieAutreVar));

			String elecChaufferieCombustibleRC = "Elec.Chaufferie.combustibleRC";
			partialModel.dispatch(elecChaufferieCombustibleRC).to(elecChaufferieCombustibleRCTantiemes).taking(calc.ratio(0.3));
			partialModel.dispatch(elecChaufferieCombustibleRC).to(elecChaufferieCombustibleRCCompteurs).taking(calc.ratio(0.7));
			String mwhChaufferieCombustibleRcVar = "mwhChaufferieCombustibleRC";
			Calculation elecChaufferieCombustibleRc = calc.resource(mwhKey, variables.valueOf(mwhChaufferieCombustibleRcVar));

			String elecChaufferieCombustibleECS = "Elec.Chaufferie.combustibleECS";
			partialModel.dispatch(elecChaufferieCombustibleECS).to(elecChaufferieCombustibleECSTantiemes).taking(calc.ratio(0.3));
			partialModel.dispatch(elecChaufferieCombustibleECS).to(elecChaufferieCombustibleECSCompteurs).taking(calc.ratio(0.7));
			String mwhChaufferieCombustibleEcsVar = "mwhChaufferieCombustibleECS";
			Calculation elecChaufferieCombustibleEcs = calc.resource(mwhKey, variables.valueOf(mwhChaufferieCombustibleEcsVar));

			String elecChaufferieCombustible = "Elec.Chaufferie.combustible";
			partialModel.dispatch(elecChaufferieCombustible).to(elecChaufferieCombustibleECS).taking(elecChaufferieCombustibleEcs);
			partialModel.dispatch(elecChaufferieCombustible).to(elecChaufferieCombustibleRC).taking(elecChaufferieCombustibleRc);
			String mwhChaufferieCombustibleVar = "mwhChaufferieCombustible";
			Calculation mwhChaufferieCombustible = calc.resource(mwhKey, variables.valueOf(mwhChaufferieCombustibleVar));

			String elecChaufferieGeneral = "Elec.Chaufferie.general";
			partialModel.dispatch(elecChaufferieGeneral).to(elecChaufferieCombustible).taking(mwhChaufferieCombustible);
			partialModel.dispatch(elecChaufferieGeneral).to(elecChaufferieAutre).taking(mwhChaufferieAutre);
			String mwhChaufferieVar = "mwhChaufferie";
			Calculation mwhChaufferie = calc.resource(mwhKey, variables.valueOf(mwhChaufferieVar));

			String elecTgbtAscenseurBoussole = "Elec.TGBT.ascenseur_boussole";
			partialModel.dispatch(elecTgbtAscenseurBoussole).to(tantièmesPcs3).taking(calc.everything());
			String mwhTgbtAscenseurBoussoleVar = "mwhTgbtAscenseurBoussole";
			Calculation mwhTgbtAscenseurBoussole = calc.resource(mwhKey, variables.valueOf(mwhTgbtAscenseurBoussoleVar));

			String elecTgbtGeneral = "Elec.TGBT.general";
			partialModel.dispatch(elecTgbtGeneral).to(elecTgbtAscenseurBoussole).taking(mwhTgbtAscenseurBoussole);
			partialModel.dispatch(elecTgbtGeneral).to(elecChaufferieGeneral).taking(mwhChaufferie);

			/* DYNAMIC SOURCE & DYNAMIC INFO */

			// TODO Create variables? Assignments here
			String factureElec = "Facture.Elec";
			partialModel.assign(factureElec, mwhKey, 100.0);
			partialModel.assign(factureElec, eurosKey, 1000.0);
			partialModel.dispatch(factureElec).to(elecTgbtGeneral).taking(calc.resource(mwhKey, 100.0));

			String factureWater = "Facture.Eau";
			partialModel.assign(factureWater, waterKey, 100.0);
			partialModel.assign(factureWater, eurosKey, 1000.0);
			partialModel.dispatch(factureWater).to(eauPotableGeneral).taking(calc.resource(waterKey, 100.0));

			String facturePoubellesBoussole = "Facture.PoubelleBoussole";
			partialModel.assign(facturePoubellesBoussole, eurosKey, 100.0);
			partialModel.dispatch(facturePoubellesBoussole).to(tantièmesPcs4).taking(calc.everything());

			/* VARIABLES */

			variables.set(eau32Var, 0.1);
			variables.set(eau33Var, 0.1);
			variables.set(eauChaufferieVar, 50.0);
			variables.set(ecs32Var, 10.0);
			variables.set(ecs33Var, 10.0);

			variables.set(mwhTgbtAscenseurBoussoleVar, 10.0);
			variables.set(mwhChaufferieVar, 50.0);
			variables.set(mwhChaufferieCombustibleVar, 30.0);
			variables.set(mwhChaufferieCombustibleEcsVar, 15.0);
			variables.set(mwhChaufferieCombustibleRcVar, 15.0);
			variables.set(mwhChaufferieAutreVar, 20.0);

			variables.set(cal32Var, 0.1);
			variables.set(cal33Var, 0.1);

			Graph.Instance graphInstance = partialModel.instantiate();

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
			Comparator<Graph.Model.ID> idComparator = comparing(Graph.Model.ID::value);
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
						Calculation calculation = link.calculation();
						EnrichedCalculation enrichedCalculation = enrichedCalculationFactory.enrich(calculation);
						Mode mode = enrichedCalculation.mode();
						Number value = enrichedCalculation.value().get();
						String calculationString = switch (mode) {
						case RATIO:
							yield ((double) value * 100) + " %";
						case TANTIEMES:
							yield (int) value + " t";
						case MWH:
							yield (double) value + " " + resourceRenderer.get(mwhKey);
						case WATER:
							yield (double) value + " " + resourceRenderer.get(waterKey);
						case SET:
							yield (double) value + " from set";
						};
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

	static interface Calculation {
		Resources compute(Resources source);

		static interface Resources {
			Optional<BigDecimal> resource(String resourceKey);

			default Resources add(Resources added) {
				Resources current = this;
				return resourceKey -> {
					Optional<BigDecimal> currentResource = current.resource(resourceKey);
					Optional<BigDecimal> addedResource = added.resource(resourceKey);
					if (!currentResource.isPresent()) {
						return addedResource;
					}
					if (!addedResource.isPresent()) {
						return currentResource;
					}
					BigDecimal currentValue = currentResource.get();
					BigDecimal addedValue = addedResource.get();
					BigDecimal newValue = currentValue.add(addedValue);
					return Optional.of(newValue);
				};
			}

			default Resources multiply(BigDecimal ratio) {
				return resourceKey -> resource(resourceKey).map(value -> value.multiply(ratio));
			}

			static Resources createEmpty() {
				return resourceKey -> Optional.empty();
			}
		}

		static interface Factory {
			Calculation resource(String resourceKey, Value<Double> value);

			default Calculation resource(String resourceKey, double value) {
				return resource(resourceKey, () -> value);
			}

			Calculation tantiemes(int tantiemes);

			Calculation ratio(double ratio);

			Calculation everything();

			Group createGroup();

			static interface Group {
				Calculation part(Value<Double> value);
			}

			static class Base implements Factory {
				@Override
				public Calculation resource(String resourceKey, Value<Double> value) {
					requireNonNull(resourceKey);
					return source -> {
						requireNonNull(source);
						BigDecimal ref = source.resource(resourceKey).orElseThrow(() -> new IllegalArgumentException("No " + resourceKey + " in " + source));
						BigDecimal ratio = BigDecimal.valueOf(value.get()).divide(ref);
						return source.multiply(ratio);
					};
				}

				@Override
				public Calculation everything() {
					return source -> source;
				}

				@Override
				public Calculation ratio(double ratio) {
					BigDecimal bigRatio = BigDecimal.valueOf(ratio);
					return source -> source.multiply(bigRatio);
				}

				@Override
				public Calculation tantiemes(int tantiemes) {
					// TODO Use group
					// TODO Constrain group sum to be 10k
					BigDecimal ratio = BigDecimal.valueOf(tantiemes).divide(BigDecimal.valueOf(10000));
					return source -> source.multiply(ratio);
				}

				@Override
				public Group createGroup() {
					var total = new Object() {
						Supplier<BigDecimal> supplier = () -> BigDecimal.ZERO;
					};
					return new Group() {
						@Override
						public Calculation part(Value<Double> value) {
							Supplier<BigDecimal> partSupplier = () -> BigDecimal.valueOf(value.get());
							Supplier<BigDecimal> oldTotalSupplier = total.supplier;
							total.supplier = () -> oldTotalSupplier.get().add(partSupplier.get());
							return source -> {
								BigDecimal partValue = partSupplier.get();
								// TODO Align with other constraints?
								BigDecimal totalValue = total.supplier.get();
								if (totalValue.compareTo(BigDecimal.ZERO) <= 0) {
									throw new IllegalStateException("No amount in " + this);
								}
								BigDecimal ratio = partValue.divide(totalValue);
								return source.multiply(ratio);
							};
						}
					};
				}
			}
		}
	}

	private static interface Graph<V, E> {
		Stream<V> vertices();

		Stream<E> edges();

		static class Model implements Graph<Model.ID, Model.Relation> {
			private final Collection<ID> ids = new LinkedHashSet<>();
			private final List<Relation> relations = new LinkedList<>();
			private final Map<Model.ID, Map<String, Double>> inputs = new HashMap<>();

			static record ID(String value) {
				ID {
					requireNonNull(value);
				}
			}

			public record Relation(ID source, Calculation calculation, ID target) {
				public Relation {
					requireNonNull(source);
					requireNonNull(calculation);
					requireNonNull(target);
				}
			}

			@Override
			public Stream<ID> vertices() {
				return ids.stream();
			}

			@Override
			public Stream<Relation> edges() {
				return relations.stream();
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

			void assign(String source, String key, double value) {
				requireNonNull(source);
				requireNonNull(key);

				Model.ID id = new Model.ID(source);
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

			Graph.Instance instantiate() {
				Map<Model.ID, Instance.Node> nodes = new HashMap<>();

				Collection<Model.ID> inputIds = inputs.keySet();
				inputIds.stream().forEach(id -> {
					requireNonNull(id);
					Map<String, Double> values = inputs.get(id);
					requireNonNull(values);
					nodes.put(id, Instance.Node.create(id, resourceKey -> {
						requireNonNull(resourceKey);
						return Optional.ofNullable(values.get(resourceKey)).map(BigDecimal::valueOf);
					}));
				});

				Comparator<Model.ID> idComparator = idComparatorAsPerRelations(relations);

				ids.stream()//
						.sorted(idComparator)//
						.filter(id -> !inputIds.contains(id))//
						.forEach(targetId -> {
							Supplier<Calculation.Resources> proxy = cache(() -> {
								var wrapper = new Object() {
									Calculation.Resources targetResources = Calculation.Resources.createEmpty();
								};
								relations.stream()//
										.filter(relation -> relation.target().equals(targetId))//
										.forEach(relation -> {
											Model.ID sourceId = relation.source();
											Instance.Node source = nodes.get(sourceId);
											Calculation.Resources sourceResources = new Calculation.Resources() {

												@Override
												public Optional<BigDecimal> resource(String resourceKey) {
													return source.get(resourceKey).map(BigDecimal::valueOf);
												}
											};

											Calculation calculation = relation.calculation();
											Calculation.Resources resource = calculation.compute(sourceResources);
											wrapper.targetResources = wrapper.targetResources.add(resource);
										});
								return wrapper.targetResources;
							});
							nodes.put(targetId, Graph.Instance.Node.create(targetId, resourceKey -> proxy.get().resource(resourceKey)));
						});

				Collection<Instance.Link> links = new LinkedList<>();
				relations.stream()//
						.map(Model.Relation::target)//
						.distinct()//
						.filter(id -> !inputIds.contains(id))//
						.forEach(targetId -> {
							requireNonNull(targetId);
							links.addAll(relations.stream()//
									.filter(relation -> relation.target().equals(targetId))//
									.map(relation -> {
										Model.ID id = relation.source();
										Instance.Node source = nodes.get(id);
										requireNonNull(source, "No node for " + id);
										Calculation calculation = relation.calculation();
										return new Instance.Link(source, calculation, nodes.get(targetId));
									}).toList());
						});

				return new Instance(nodes.values(), links);
			}

			private Comparator<Model.ID> idComparatorAsPerRelations(List<Model.Relation> relations) {
				Map<Model.ID, Set<Model.ID>> orderedIds = relations.stream().collect(Collectors.groupingBy(Model.Relation::source, Collectors.mapping(Model.Relation::target, Collectors.toSet())));
				BiPredicate<Model.ID, Model.ID> isBefore = (id1, id2) -> {
					Set<Model.ID> ids = Set.of(id1);
					while (true) {
						Set<Model.ID> nexts = ids.stream().map(orderedIds::get).filter(not(Objects::isNull)).reduce(new HashSet<>(), (s1, s2) -> {
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
				Comparator<Model.ID> idValueComparator = Comparator.comparing(Model.ID::value);
				Comparator<Model.ID> idComparator = (id1, id2) -> {
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
				Model.ID id();

				Optional<Double> get(String resourceKey);

				static Node create(Model.ID id, Calculation.Resources resourceProvider) {
					return new Node() {
						@Override
						public Model.ID id() {
							return id;
						}

						@Override
						public Optional<Double> get(String resourceKey) {
							return resourceProvider.resource(resourceKey).map(BigDecimal::doubleValue);
						}
					};
				}

			}

			static record Link(Node source, Calculation calculation, Node target) {
				public Link {
					requireNonNull(source);
					requireNonNull(calculation);
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

	static interface Value<T> {
		T get();
	}

	static class Variables {
		Map<String, Double> values = new HashMap<>();

		public double get(String variable) {
			return values.compute(variable, (key, value) -> {
				if (value == null) {
					throw new IllegalArgumentException("No value for variable: " + variable);
				}
				return value;
			});
		}

		public void set(String variable, double value) {
			values.compute(variable, (key, oldValue) -> {
				if (oldValue != null) {
					throw new IllegalArgumentException("Variable " + variable + " set with " + oldValue + ", cannot set: " + value);
				}
				return value;
			});
		}

		public Value<Double> valueOf(String variable) {
			return () -> get(variable);
		}
	}
}
