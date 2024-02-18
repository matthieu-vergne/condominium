package fr.vergne.condominium;

import static java.nio.file.Files.createDirectories;
import static java.nio.file.Files.find;
import static java.nio.file.Files.isRegularFile;
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
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
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

			LOGGER.accept("Redact script");
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			PrintStream scriptStream = new PrintStream(out, false, Charset.forName("UTF-8"));
			scriptStream.println("@startuml");
			scriptStream.println("title " + LocalDateTime.now());
			scriptStream.println("left to right direction");

			interface X {
				String id();

				double mwh();

				double euros();

				double water();

				static X create(String id, double mwh, double euros, double water) {
					return new X() {

						@Override
						public String id() {
							return id;
						}

						@Override
						public double mwh() {
							return mwh;
						}

						@Override
						public double euros() {
							return euros;
						}

						@Override
						public double water() {
							return water;
						}
					};
				}

				static X create(String id, Supplier<X> proxy) {
					return new X() {

						@Override
						public String id() {
							return id;
						}

						@Override
						public double mwh() {
							return proxy.get().mwh();
						}

						@Override
						public double euros() {
							return proxy.get().euros();
						}

						@Override
						public double water() {
							return proxy.get().water();
						}
					};
				}

				enum Mode {
					RATIO, SET, MWH, WATER, TANTIEMES;
				}

				interface Calculation {

					Number value();

					X.Mode mode();

					double ratio(X source);

					static interface SetX {
						void register(X.Calculation calculation);

						Stream<X.Calculation> stream();

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
							public double ratio(X source) {
								return ratio;
							}

							@Override
							public Mode mode() {
								return Mode.RATIO;
							}
						};
					}

					static Calculation fromMwh(double mwh) {
						return new Calculation() {

							@Override
							public Number value() {
								return mwh;
							}

							@Override
							public double ratio(X source) {
								double ref = source.mwh();
								if (ref <= 0) {
									throw new IllegalArgumentException("No MWh in " + source.id());
								}
								return mwh / ref;
							}

							@Override
							public Mode mode() {
								return Mode.MWH;
							}
						};
					}

					static Calculation fromSet(double value, SetX set) {
						Calculation calculation = new Calculation() {

							@Override
							public Number value() {
								return value;
							}

							@Override
							public double ratio(X source) {
								double ref = set.stream()//
										.map(X.Calculation::value)//
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

					static Calculation fromWater(double water) {
						return new Calculation() {

							@Override
							public Number value() {
								return water;
							}

							@Override
							public double ratio(X source) {
								double ref = source.water();
								if (ref <= 0) {
									throw new IllegalArgumentException("No m³ in " + source.id());
								}
								return water / ref;
							}

							@Override
							public Mode mode() {
								return Mode.WATER;
							}
						};
					}

					static Calculation fromTantiemes(int tantiemes) {
						return new Calculation() {
							@Override
							public Number value() {
								return tantiemes;
							}

							@Override
							public double ratio(X source) {
								return (double) tantiemes / 10000;
							}

							@Override
							public Mode mode() {
								return Mode.TANTIEMES;
							}
						};
					}

					static SetX createSet() {
						Set<X.Calculation> sources = new HashSet<>();
						return new SetX() {
							private boolean released = false;

							public void register(X.Calculation calculation) {
								sources.add(calculation);
							};

							@Override
							public void release() {
								this.released = true;
							}

							@Override
							public Stream<X.Calculation> stream() {
								if (!released) {
									throw new IllegalStateException("Not relased yet");
								}
								return sources.stream();
							}
						};
					}
				}

				record Y(X source, Calculation calculation, X target) {
				}

				static record Z(X target, Collection<Y> relations) {
					static Z from(String id, Map<X, Calculation> map) {
						Supplier<X> proxy = () -> {
							var wrapper = new Object() {
								BigDecimal mwh = BigDecimal.ZERO;
								BigDecimal water = BigDecimal.ZERO;
								BigDecimal euros = BigDecimal.ZERO;
							};
							map.entrySet().stream().forEach(entry -> {
								X source = entry.getKey();
								Calculation calculation = entry.getValue();
								BigDecimal ratio = BigDecimal.valueOf(calculation.ratio(source));
								wrapper.mwh = wrapper.mwh.add(BigDecimal.valueOf(source.mwh()).multiply(ratio));
								wrapper.water = wrapper.water.add(BigDecimal.valueOf(source.water()).multiply(ratio));
								wrapper.euros = wrapper.euros.add(BigDecimal.valueOf(source.euros()).multiply(ratio));
							});
							double mwh = wrapper.mwh.doubleValue();
							double water = wrapper.water.doubleValue();
							double euros = wrapper.euros.doubleValue();
							return X.create(id, mwh, euros, water);
						};
						X target = X.create(id, proxy);
						List<Y> relations = map.entrySet().stream().map(entry -> {
							X source = entry.getKey();
							Calculation calculation = entry.getValue();
							return new Y(source, calculation, target);
						}).toList();
						return new Z(target, relations);
					}
				}
			}
			Consumer<X> objectPrinter = x -> {
				scriptStream.println("map " + x.id() + " {");
				scriptStream.println("	MWh => " + x.mwh());
				scriptStream.println("	m³ => " + x.water());
				scriptStream.println("	€ => " + x.euros());
				scriptStream.println("}");
			};
			Function<X.Mode, Function<X.Calculation, String>> modeRenderer = mode -> {
				switch (mode) {
				case RATIO: {
					return calculation -> ((double) calculation.value() * 100) + " %";
				}
				case TANTIEMES: {
					return calculation -> (int) calculation.value() + " t";
				}
				case MWH: {
					return calculation -> (double) calculation.value() + " MWh";
				}
				case WATER: {
					return calculation -> (double) calculation.value() + " m³";
				}
				case SET: {
					return calculation -> (double) calculation.value() + " from set";
				}
				default:
					throw new IllegalArgumentException("Unsupported value: " + mode);
				}
			};
			Consumer<X.Y> relationPrinter = y -> {
				X.Calculation calculation = y.calculation();
				X.Mode mode = calculation.mode();
				Function<X.Calculation, String> calculationRenderer = modeRenderer.apply(mode);
				String calculationString = calculationRenderer.apply(calculation);
				scriptStream.println(y.source().id() + " --> " + y.target().id() + " : " + calculationString);
			};
			var printer = new Object() {
				Runnable runnable = () -> {
				};

				void add(Runnable next) {
					Runnable previous = this.runnable;
					this.runnable = () -> {
						previous.run();
						next.run();
					};
				}

				void print() {
					runnable.run();
				}
			};
			Function<X, X> g = x -> {
				printer.add(() -> objectPrinter.accept(x));
				return x;
			};
			BiFunction<String, Map<X, X.Calculation>, X> f = (id, sources) -> {
				X.Z from7 = X.Z.from(id, sources);
				X target = from7.target();
				printer.add(() -> {
					objectPrinter.accept(target);
					from7.relations().forEach(relationPrinter);
				});
				return target;
			};

			X factureElec = g.apply(X.create("Facture.Elec", 100.0, 1000.0, 0.0));
			X factureWater = g.apply(X.create("Facture.Eau", 0.0, 1000.0, 100.0));
			X facturePoubellesBoussole = g.apply(X.create("Facture.PoubelleBoussole", 0.0, 100.0, 0.0));

			X eauPotableGeneral = f.apply("Eau.Potable.general", Map.of(factureWater, X.Calculation.fromWater(100.0)));
			X eauPotableChaufferie = f.apply("Eau.Potable.chaufferie",
					Map.of(eauPotableGeneral, X.Calculation.fromWater(50.0)));

			X elecTgbtGeneral = f.apply("Elec.TGBT.general", Map.of(factureElec, X.Calculation.fromMwh(100.0)));
			X elecTgbtAscenseurBoussole = f.apply("Elec.TGBT.ascenseur_boussole",
					Map.of(elecTgbtGeneral, X.Calculation.fromMwh(10.0)));
			X elecChaufferie = f.apply("Elec.Chaufferie.general", Map.of(elecTgbtGeneral, X.Calculation.fromMwh(50.0)));

			X elecChaufferieCombustible = f.apply("Elec.Chaufferie.combustible",
					Map.of(elecChaufferie, X.Calculation.fromMwh(30.0)));
			X elecChaufferieCombustibleECS = f.apply("Elec.Chaufferie.combustibleECS",
					Map.of(elecChaufferieCombustible, X.Calculation.fromMwh(15.0)));
			X elecChaufferieCombustibleECSTantiemes = f.apply("Elec.Chaufferie.combustibleECSTantiemes",
					Map.of(elecChaufferieCombustibleECS, X.Calculation.fromRatio(0.3)));
			X elecChaufferieCombustibleECSCompteurs = f.apply(
					"Elec.Chaufferie.combustibleECSCompteurs",
					Map.of(elecChaufferieCombustibleECS, X.Calculation.fromRatio(0.7)));
			X elecChaufferieCombustibleRC = f.apply("Elec.Chaufferie.combustibleRC",
					Map.of(elecChaufferieCombustible, X.Calculation.fromMwh(15.0)));
			X elecChaufferieCombustibleRCTantiemes = f.apply(
					"Elec.Chaufferie.combustibleRCTantiemes",
					Map.of(elecChaufferieCombustibleRC, X.Calculation.fromRatio(0.3)));
			X elecChaufferieCombustibleRCCompteurs = f.apply(
					"Elec.Chaufferie.combustibleRCCompteurs",
					Map.of(elecChaufferieCombustibleRC, X.Calculation.fromRatio(0.7)));
			X elecChaufferieAutre = f.apply("Elec.Chaufferie.autre",
					Map.of(elecChaufferie, X.Calculation.fromMwh(20.0)));
			X elecChaufferieAutreMesures = f.apply("Elec.Chaufferie.autreMesures",
					Map.of(elecChaufferieAutre, X.Calculation.fromRatio(0.5)));
			X elecChaufferieAutreTantiemes = f.apply("Elec.Chaufferie.autreTantiemes",
					Map.of(elecChaufferieAutre, X.Calculation.fromRatio(0.5)));
			
			X tantièmesPcs3 = f.apply("Tantiemes.PCS3", Map.of(elecTgbtAscenseurBoussole, X.Calculation.fromAll()));
			X tantièmesPcs4 = f.apply("Tantiemes.PCS4", Map.of(facturePoubellesBoussole, X.Calculation.fromAll()));
			X tantièmesChauffage = f.apply("Tantiemes.ECS_Chauffage", Map.of(//
					elecChaufferieCombustibleECSTantiemes, X.Calculation.fromAll(), //
					elecChaufferieCombustibleRCTantiemes, X.Calculation.fromRatio(0.5), //
					elecChaufferieAutreTantiemes, X.Calculation.fromRatio(0.5)//
			));
			X tantièmesRafraichissement = f.apply("Tantiemes.Rafraichissement", Map.of(//
					elecChaufferieCombustibleRCTantiemes, X.Calculation.fromRatio(0.5), //
					elecChaufferieAutreTantiemes, X.Calculation.fromRatio(0.5)//
			));

			// TODO Retrieve lots tantiemes from CSV(Lots, PCg/s)
			X.Calculation.SetX setCalorifique = X.Calculation.createSet();
			X.Calculation.SetX setECS = X.Calculation.createSet();

			X eauPotableFroideLot32 = f.apply("Eau.Potable.Froide.lot32",
					Map.of(eauPotableGeneral, X.Calculation.fromWater(0.1)));
			double ecs32 = 10.0;
			X.Calculation ecs32b = X.Calculation.fromSet(ecs32, setECS);
			X eauPotableChaudeLot32 = f.apply("Eau.Potable.Chaude.lot32", Map.of(//
					elecChaufferieCombustibleECSCompteurs, ecs32b, //
					eauPotableChaufferie, X.Calculation.fromWater(ecs32)//
			));
			X.Calculation calorifique32 = X.Calculation.fromSet(0.1, setCalorifique);
			X elecCalorifiqueLot32 = f.apply("Elec.Calorifique.lot32", Map.of(//
					elecChaufferieCombustibleRCCompteurs, calorifique32, //
					elecChaufferieAutreMesures, calorifique32//
			));
			X lot32 = f.apply("Lot.32", Map.of(//
					tantièmesPcs3, X.Calculation.fromTantiemes(317), //
					tantièmesPcs4, X.Calculation.fromTantiemes(347), //
					tantièmesChauffage, X.Calculation.fromTantiemes(127), //
					tantièmesRafraichissement, X.Calculation.fromTantiemes(182), //
					eauPotableFroideLot32, X.Calculation.fromAll(), //
					eauPotableChaudeLot32, X.Calculation.fromAll(), //
					elecCalorifiqueLot32, X.Calculation.fromAll()//
			));

			X eauPotableFroideLot33 = f.apply("Eau.Potable.Froide.lot33",
					Map.of(eauPotableGeneral, X.Calculation.fromWater(0.1)));
			double ecs33 = 10.0;
			X.Calculation ecs33b = X.Calculation.fromSet(ecs33, setECS);
			X eauPotableChaudeLot33 = f.apply("Eau.Potable.Chaude.lot33", Map.of(//
					elecChaufferieCombustibleECSCompteurs, ecs33b, //
					eauPotableChaufferie, X.Calculation.fromWater(ecs33)//
			));
			X.Calculation calorifique33 = X.Calculation.fromSet(0.1, setCalorifique);
			X elecCalorifiqueLot33 = f.apply("Elec.Calorifique.lot33", Map.of(//
					elecChaufferieCombustibleRCCompteurs, calorifique33, //
					elecChaufferieAutreMesures, calorifique33//
			));
			X lot33 = f.apply("Lot.33", Map.of(//
					tantièmesPcs3, X.Calculation.fromTantiemes(449), //
					tantièmesPcs4, X.Calculation.fromTantiemes(494), //
					tantièmesChauffage, X.Calculation.fromTantiemes(179), //
					tantièmesRafraichissement, X.Calculation.fromTantiemes(256), //
					eauPotableFroideLot33, X.Calculation.fromAll(), //
					eauPotableChaudeLot33, X.Calculation.fromAll(), //
					elecCalorifiqueLot33, X.Calculation.fromAll()//
			));

			setCalorifique.release();
			setECS.release();

			printer.print();

//			scriptStream.println("Facture ..> Elec");
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

}
