package fr.vergne.condominium;

import static fr.vergne.condominium.Main2.ComputationUtil.isPracticallyZero;
import static java.nio.file.Files.createDirectories;
import static java.nio.file.Files.find;
import static java.nio.file.Files.isRegularFile;
import static java.util.Comparator.comparing;
import static java.util.Objects.requireNonNull;
import static java.util.function.Predicate.not;
import static java.util.stream.Collectors.counting;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
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
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

import fr.vergne.condominium.Main2.Calculation.Factory;
import fr.vergne.condominium.Main2.Calculation.Factory.Group;
import fr.vergne.condominium.Main2.Calculation.Resources;
import fr.vergne.condominium.core.mail.Mail;
import fr.vergne.condominium.core.mail.Mail.Body;
import fr.vergne.condominium.core.mail.MimeType;
import fr.vergne.condominium.core.mail.SoftReferencedMail;
import fr.vergne.condominium.core.monitorable.Issue;
import fr.vergne.condominium.core.monitorable.Question;
import fr.vergne.condominium.core.parser.mbox.MBoxParser;
import fr.vergne.condominium.core.parser.yaml.DistrConfiguration;
import fr.vergne.condominium.core.parser.yaml.GraphConfiguration;
import fr.vergne.condominium.core.parser.yaml.VarsConfiguration;
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

	private static final String VARIABLE_PREFIX = "$";
	private static final String DEFINED_KEY_SEPARATOR = ":";

	public static void main(String[] args) throws IOException {
		Path confFolderPath = Paths.get(System.getProperty("confFolder"));
		Path outFolderPath = Paths.get(System.getProperty("outFolder"));
		outFolderPath.toFile().mkdirs();
		Path confTantiemesPath = confFolderPath.resolve("tantiemes.yaml");
		Path confLotsPath = confFolderPath.resolve("lots.yaml");
		Path confDistrPath = confFolderPath.resolve("distr.yaml");
		Path confFactPath = confFolderPath.resolve("fact.yaml");
		Path confVarsPath = confFolderPath.resolve("vars.yaml");
		Path confGraphPath = confFolderPath.resolve("graph.yaml");
		Path path = outFolderPath.resolve("graphCharges.plantuml");
		Path svgPath = outFolderPath.resolve("graphCharges.svg");

		LOGGER.accept("=================");

		LOGGER.accept("Read tantiemes conf");
		DistrConfiguration confLots = DistrConfiguration.parser().apply(confLotsPath);
		DistrConfiguration confTantiemes = DistrConfiguration.parser().apply(confTantiemesPath);
		DistrConfiguration confDistr = DistrConfiguration.parser().apply(confDistrPath);
		DistrConfiguration confFact = DistrConfiguration.parser().apply(confFactPath);
		VarsConfiguration confVars = VarsConfiguration.parser().apply(confVarsPath);
		GraphConfiguration confGraph = GraphConfiguration.parser().apply(confGraphPath);

		LOGGER.accept("=================");
		{
			// TODO Filter on condominium lots
			LOGGER.accept("Create charges diagram");
//			MailHistory.Factory mailHistoryFactory = new MailHistory.Factory.WithPlantUml(confProfiles, LOGGER);
//			MailHistory mailHistory = mailHistoryFactory.create(mails);
//			mailHistory.writeScript(historyScriptPath);
//			mailHistory.writeSvg(historyPath);

			Collection<String> resourceKeys = confGraph.getResources().keySet();
			Graph.Instance.Validator.Aggregator graphValidator = new Graph.Instance.Validator.Aggregator(resourceKeys);

			Calculation.Factory.Base baseCalculationFactory = new Calculation.Factory.Base(graphValidator);

			// TODO Make instanciable
			// TODO Instantiate ABSOLUTE on the fly for reduction
			// TODO Create others from graph conf
			// TODO Reduce MWH and WATER to RESOURCE or RESOURCE(key)?
			enum Mode {
				RATIO, GROUP, MWH, WATER, TANTIEMES, ABSOLUTE
			}
			record EnrichedCalculation(Calculation delegate, Mode mode, Value<BigDecimal> value) implements Calculation {
				@Override
				public Calculation.Resources compute(Calculation.Resources source) {
					return delegate.compute(source);
				}
			}
			Map<Mode, String> modeResources = confGraph.getResources().entrySet().stream()//
					.filter(entry -> entry.getValue().getMode() != null)//
					.collect(toMap(entry -> Mode.valueOf(entry.getValue().getMode()), Entry::getKey));
			var enrichedCalculationFactory = new Calculation.Factory() {

				private final Calculation.Factory factory = baseCalculationFactory;
				private final Map<String, Mode> resourceModes = modeResources.entrySet().stream()//
						.collect(toMap(Entry::getValue, Entry::getKey));

				EnrichedCalculation enrich(Calculation calculation) {
					return (EnrichedCalculation) calculation;
				}

				private final Map<EnrichedCalculation, EnrichedCalculation> original = new HashMap<>();

				@Override
				public Calculation absolute(Calculation calculation, Resources source) {
					if (original.containsKey(calculation)) {
						throw new IllegalArgumentException("Already absolute: " + calculation);
					}

					EnrichedCalculation enrichedCalculation = enrich(calculation);
					Mode mode = enrichedCalculation.mode;
					if (List.of(Mode.RATIO, Mode.WATER, Mode.MWH, Mode.GROUP, Mode.TANTIEMES).contains(mode)) {
						Calculation absolute = newSource -> enrichedCalculation.delegate().compute(source);
						EnrichedCalculation absolutedCalculation = new EnrichedCalculation(absolute, Mode.ABSOLUTE, null);
						original.put(absolutedCalculation, enrichedCalculation);
						return absolutedCalculation;
					} else {
						throw new UnsupportedOperationException("Unsupported mode: " + mode);
					}
				}

				@Override
				public Calculation relativize(Calculation calculation, Resources source) {
					EnrichedCalculation enrichedCalculation = enrich(calculation);
					if (!enrichedCalculation.mode().equals(Mode.ABSOLUTE)) {
						throw new IllegalArgumentException("Non absolute calculation: " + calculation);
					}

					Queue<EnrichedCalculation> waitingProcessing = new LinkedList<>();
					Collection<EnrichedCalculation> leafCalculation = new LinkedList<>();
					waitingProcessing.add(enrichedCalculation);
					while (!waitingProcessing.isEmpty()) {
						EnrichedCalculation processed = waitingProcessing.poll();
						List<EnrichedCalculation> content = aggregates.get(processed);
						if (content == null) {
							leafCalculation.add(processed);
						} else {
							waitingProcessing.addAll(content);
						}
					}
					Mode relativeMode = null;
					Collection<EnrichedCalculation> relativeCalculations = new LinkedList<>();
					for (EnrichedCalculation leaf : leafCalculation) {
						EnrichedCalculation relativeCalculation = original.get(leaf);
						relativeCalculations.add(relativeCalculation);
						Mode mode = relativeCalculation.mode();
						if (List.of(Mode.RATIO, Mode.WATER, Mode.MWH, Mode.GROUP, Mode.TANTIEMES).contains(mode)) {
							if (relativeMode == null) {
								relativeMode = mode;
							} else if (!relativeMode.equals(mode)) {
								throw new IllegalStateException("Incompatible modes: " + relativeMode + " vs. " + mode);
							} else {
								// Compatible modes so far
							}
						} else {
							throw new UnsupportedOperationException("Not supported: " + mode);
						}
					}

					if (relativeMode.equals(Mode.RATIO)) {
						Resources resources = enrichedCalculation.compute(null);
						BigDecimal ratio = resourceKeys.stream()//
								.map(resourceKey -> {
									Optional<BigDecimal> opt = resources.resource(resourceKey);
									if (opt.isEmpty()) {
										return Optional.<BigDecimal>empty();
									}
									BigDecimal keyValue = opt.get();
									if (ComputationUtil.isPracticallyZero(keyValue)) {
										return Optional.of(keyValue);
									}

									Optional<BigDecimal> opt2 = source.resource(resourceKey);
									if (opt2.isEmpty()) {
										throw new IllegalStateException("No resource " + resourceKey + " to consume, asking: " + keyValue);
									}
									BigDecimal keyTotal = opt2.get();
									BigDecimal keyRatio = ComputationUtil.divide(keyValue, keyTotal);
									return Optional.of(keyRatio);
								})//
								.filter(Optional::isPresent).map(Optional::get)//
								.reduce((r1, r2) -> {
									if (ComputationUtil.isPracticallyZero(r1.subtract(r2))) {
										return r1;
									} else {
										throw new IllegalStateException("Incompatible ratios: " + r1 + " vs " + r2);
									}
								})//
								.orElseThrow(() -> new IllegalStateException("No ratio computed for: " + calculation))//
								.stripTrailingZeros();

						return new EnrichedCalculation(src -> src.multiply(ratio), Mode.RATIO, () -> ratio);
					} else if (relativeMode.equals(Mode.WATER)) {
						Resources resources = enrichedCalculation.compute(null);
						String resourceKey = modeResources.get(relativeMode);
						BigDecimal resource = resources.resource(resourceKey).orElseThrow(() -> new IllegalStateException(Mode.WATER + " mode but no " + resourceKey + " resource"));
						return new EnrichedCalculation(src -> resources, Mode.WATER, () -> resource);
					} else if (relativeMode.equals(Mode.TANTIEMES)) {
						Resources resources = enrichedCalculation.compute(null);
						BigDecimal ratio = resourceKeys.stream()//
								.map(resourceKey -> {
									Optional<BigDecimal> opt = resources.resource(resourceKey);
									if (opt.isEmpty()) {
										return Optional.<BigDecimal>empty();
									}
									BigDecimal keyValue = opt.get();
									Optional<BigDecimal> opt2 = source.resource(resourceKey);
									if (opt2.isEmpty()) {
										throw new IllegalStateException("No resource " + resourceKey + " to consume, asking: " + keyValue);
									}
									BigDecimal keyTotal = opt2.get();
									BigDecimal keyRatio = ComputationUtil.divide(keyValue, keyTotal);
									return Optional.of(keyRatio);
								})//
								.filter(Optional::isPresent).map(Optional::get)//
								.reduce((r1, r2) -> {
									if (ComputationUtil.isPracticallyZero(r1.subtract(r2))) {
										return r1;
									} else {
										throw new IllegalStateException("Incompatible ratios: " + r1 + " vs " + r2);
									}
								})//
								.orElseThrow(() -> new IllegalStateException("No ratio computed for: " + calculation))//
								.stripTrailingZeros();

						Value<BigDecimal> value = () -> {
							return relativeCalculations.stream().map(EnrichedCalculation::value).map(Value::get).reduce(BigDecimal::add).orElseThrow();
						};
						return new EnrichedCalculation(src -> src.multiply(ratio), Mode.TANTIEMES, value);
					} else if (relativeMode.equals(Mode.GROUP)) {
						Resources resources = enrichedCalculation.compute(null);
						BigDecimal ratio = resourceKeys.stream()//
								.map(resourceKey -> {
									Optional<BigDecimal> opt = resources.resource(resourceKey);
									if (opt.isEmpty()) {
										return Optional.<BigDecimal>empty();
									}
									BigDecimal keyValue = opt.get();
									Optional<BigDecimal> opt2 = source.resource(resourceKey);
									if (opt2.isEmpty()) {
										throw new IllegalStateException("No resource " + resourceKey + " to consume, asking: " + keyValue);
									}
									BigDecimal keyTotal = opt2.get();
									BigDecimal keyRatio = ComputationUtil.divide(keyValue, keyTotal);
									return Optional.of(keyRatio);
								})//
								.filter(Optional::isPresent).map(Optional::get)//
								.reduce((r1, r2) -> {
									if (ComputationUtil.isPracticallyZero(r1.subtract(r2))) {
										return r1;
									} else {
										throw new IllegalStateException("Incompatible ratios: " + r1 + " vs " + r2);
									}
								})//
								.orElseThrow(() -> new IllegalStateException("No ratio computed for: " + calculation))//
								.stripTrailingZeros();

						Value<BigDecimal> value = () -> {
							return relativeCalculations.stream().map(EnrichedCalculation::value).map(Value::get).reduce(BigDecimal::add).orElseThrow();
						};
						return new EnrichedCalculation(src -> src.multiply(ratio), Mode.GROUP, value);
					} else {
						throw new UnsupportedOperationException("Not supported: " + relativeMode);
					}
				}

				private final Map<EnrichedCalculation, List<EnrichedCalculation>> aggregates = new HashMap<>();

				@Override
				public Calculation aggregate(Calculation calculation1, Calculation calculation2) {
					EnrichedCalculation first = (EnrichedCalculation) calculation1;
					EnrichedCalculation next = (EnrichedCalculation) calculation2;

					Mode mode1 = first.mode();
					Mode mode2 = next.mode();
					if (!mode1.equals(mode2)) {
						throw new IllegalArgumentException("Calculation mode incompatible with " + mode1 + ": " + mode2);
					}
					Mode mode = mode1;

					if (!Set.of(Mode.ABSOLUTE, Mode.MWH, Mode.WATER, Mode.TANTIEMES).contains(mode)) {
						throw new IllegalArgumentException("Cannot aggregate reliably: " + mode);
					}

					Value<BigDecimal> value1 = first.value;
					Value<BigDecimal> value2 = next.value();
					EnrichedCalculation aggregatedCalculation = new EnrichedCalculation(resources -> first.delegate().compute(resources).add(next.delegate().compute(resources)), mode, () -> value1.get().add(value2.get()));
					aggregates.put(aggregatedCalculation, List.of(first, next));
					return aggregatedCalculation;
				}

				@Override
				public Calculation resource(String resourceKey, Value<BigDecimal> value) {
					return new EnrichedCalculation(factory.resource(resourceKey, value), resourceModes.get(resourceKey), value);
				}

				@Override
				public Calculation everything() {
					return new EnrichedCalculation(factory.everything(), Mode.RATIO, () -> BigDecimal.ONE);
				}

				@Override
				public Calculation.Factory.Group createGroup() {
					return createGroup(Mode.GROUP);
				}

				public Calculation.Factory.Group createRatioGroup() {
					Group group = createGroup(Mode.RATIO);
					group.constrainsValue(isBetween(BigDecimal.ZERO, BigDecimal.ONE));
					group.constrainsTotal(sumsUpTo(new BigDecimal("1")));
					return group;
				}

				public Calculation.Factory.Group createTantiemesGroup() {
					Group group = createGroup(Mode.TANTIEMES);
					group.constrainsValue(isAtLeast(BigDecimal.ZERO));
					group.constrainsTotal(sumsUpTo(new BigDecimal("10000")));
					return group;
				}

				private Calculation.Factory.Group createGroup(Mode mode) {
					Calculation.Factory.Group group = factory.createGroup();
					return new Calculation.Factory.Group() {
						@Override
						public Calculation part(Value<BigDecimal> value) {
							return new EnrichedCalculation(group.part(value), mode, value);
						}

						@Override
						public void constrainsValue(BiConsumer<BigDecimal, IllegalArgumentException> validator) {
							group.constrainsValue(validator);
						}

						@Override
						public void constrainsTotal(Consumer<BigDecimal> validator) {
							group.constrainsTotal(validator);
						}
					};
				}
			};
			var calc = enrichedCalculationFactory;

			/* GRAPH BUILDING */
			Data data = new Data(new HashMap<>(), new HashMap<>());
			Variables variables = new Variables();
			Graph.Model graphModel = new Graph.Model();
			confLots.forEach((lot, definition) -> feedGraphModel(data, graphModel, variables, calc, calc::createGroup, calc::createRatioGroup, lot, definition));
			// TODO Retrieve tantiemes from CSV converted to conf
			confTantiemes.forEach((tantiemes, definition) -> feedGraphModel(data, graphModel, variables, calc, calc::createTantiemesGroup, calc::createRatioGroup, tantiemes, definition));
			confDistr.forEach((id, definition) -> feedGraphModel(data, graphModel, variables, calc, calc::createGroup, calc::createRatioGroup, id, definition));
			confFact.forEach((id, definition) -> feedGraphModel(data, graphModel, variables, calc, calc::createGroup, calc::createRatioGroup, id, definition));
			confVars.forEach((id, value) -> feedVariables(variables, id, value));

			// Add global validators after specific validators were introduced in model building
			graphValidator.addValidator(Graph.Instance.Validator.checkLinksFullyConsumeTheirSourceNode());
			graphValidator.addValidator(Graph.Instance.Validator.checkLinksFullyFeedTheirTargetNode());
			graphValidator.addValidator(Graph.Instance.Validator.checkResourcesFromRootsAmountToResourcesFromLeaves());

			Graph.Instance graphInstance = createGraph(graphModel, graphValidator, calc, id -> id.value().matches(confGraph.getDisplayedLotRegex()), confGraph.getMergedName(), resourceKeys);

			String title = "Charges";// "Charges " + DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(LocalDateTime.now());

			LOGGER.accept("Redact script");
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			PrintStream scriptStream = new PrintStream(out, false, Charset.forName("UTF-8"));
			scriptStream.println("@startuml");
			scriptStream.println("title \"" + title + "\"");
			scriptStream.println("left to right direction");
			Map<String, String> resourceRenderer = confGraph.getResources().entrySet().stream().collect(toMap(//
					entry -> entry.getKey(), //
					entry -> entry.getValue().getRender()//
			));
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
						BigDecimal value = enrichedCalculation.value().get().round(MathContext.DECIMAL64).stripTrailingZeros();
						String calculationString = switch (mode) {
						case RATIO:
							yield value.multiply(new BigDecimal("100")).stripTrailingZeros().toPlainString() + " %";
						case TANTIEMES:
							yield value.toPlainString() + " t";
						case MWH, WATER:
							yield value.toPlainString() + " " + resourceRenderer.get(modeResources.get(mode));
						case GROUP:
							yield value.toPlainString() + " from group";
						case ABSOLUTE:// TODO Remove? Should be temporary
							throw new IllegalStateException("Not supposed to be here");
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

	private static void feedVariables(Variables variables, String id, Object valueRef) {
		variables.set(id, createRawValue(variables, valueRef));
	}

	private static void feedGraphModel(Data data, Graph.Model graphModel, Variables variables, Factory calc, Supplier<Group> resourceGroupFactory, Supplier<Group> ratioGroupFactory, String nodeId, Map<String, Map<String, Object>> nodeDefinition) {
		String consumeKey = "consume";
		Map<String, Object> consumeMap = nodeDefinition.get(consumeKey);
		if (consumeMap != null) {
			consumeMap.forEach((sourceId, calculationRef) -> {
				Calculation calculation = createCalculation(data, variables, calc, resourceGroupFactory, ratioGroupFactory, sourceId, calculationRef);
				graphModel.dispatch(sourceId).to(nodeId).taking(calculation);
			});
		} else {
			System.out.println("No consume for " + nodeId);
		}

		String dispatchKey = "dispatch";
		Map<String, Object> dispatchMap = nodeDefinition.get(dispatchKey);
		if (dispatchMap != null) {
			dispatchMap.forEach((targetId, calculationRef) -> {
				Calculation calculation = createCalculation(data, variables, calc, resourceGroupFactory, ratioGroupFactory, nodeId, calculationRef);
				graphModel.dispatch(nodeId).to(targetId).taking(calculation);
			});
		} else {
			System.out.println("No dispatch for " + nodeId);
		}

		String defineKey = "define";
		Map<String, Object> defineMap = nodeDefinition.get(defineKey);
		if (defineMap != null) {
			defineMap.forEach((key, calculationRef) -> {
				Calculation calculation = createCalculation(data, variables, calc, resourceGroupFactory, ratioGroupFactory, nodeId, calculationRef);
				data.defined.computeIfAbsent(key, xk -> new HashMap<>()).put(nodeId, calculation);
			});
		} else {
			System.out.println("No define for " + nodeId);
		}

		String assignKey = "assign";
		Map<String, Object> assignMap = nodeDefinition.get(assignKey);
		if (assignMap != null) {
			assignMap.forEach((key, valueRef) -> {
				graphModel.assign(nodeId, key, createRawValue(variables, valueRef));
			});
		} else {
			System.out.println("No define for " + nodeId);
		}

		nodeDefinition.keySet().stream()//
				.filter(key -> !List.of(consumeKey, dispatchKey, defineKey, assignKey).contains(key))//
				.findAny().ifPresent(key -> {
					throw new UnsupportedOperationException("Not supported: " + key);
				});
	}

	private static Calculation createCalculation(Data data, Variables variables, Factory calc, Supplier<Group> groupFactory, Supplier<Group> ratioGroupFactory, String sourceId, Object calculationRef) {
		if (calculationRef instanceof String calculationStr) {
			if (calculationStr.endsWith("%")) {
				String numberStr = calculationStr.substring(0, calculationStr.length() - 1);
				BigDecimal ratio = new BigDecimal(numberStr).movePointLeft(2);
				Group group = data.groups.computeIfAbsent(sourceId, xk -> ratioGroupFactory.get());
				return group.part(ratio);
			} else if (calculationStr.contains(DEFINED_KEY_SEPARATOR)) {
				String[] split = calculationStr.split(DEFINED_KEY_SEPARATOR);
				String targetId = split[0];
				String key = split[1];
				return data.defined.get(key).get(targetId);
			} else {
				throw new UnsupportedOperationException("Not supported String: " + calculationStr);
			}
		} else if (calculationRef instanceof DistrConfiguration.GroupValue groupValue) {
			String groupKey = groupValue.getGroupKey();
			String valueRef = groupValue.getValueRef();
			Value<BigDecimal> value = createValue(variables, valueRef);
			Group group = data.groups.computeIfAbsent(groupKey, xk -> groupFactory.get());
			return group.part(value);
		} else if (calculationRef instanceof DistrConfiguration.ResourceValue resourceValue) {
			String resourceKey = resourceValue.getResourceKey();
			String valueRef = resourceValue.getValueRef();
			Value<BigDecimal> value = createValue(variables, valueRef);
			return calc.resource(resourceKey, value);
		} else {
			throw new UnsupportedOperationException("Not supported: " + calculationRef);
		}
	}

	private static BigDecimal createRawValue(Variables variables, Object valueRef) {
		BigDecimal value;
		if (valueRef instanceof Double doubleValue) {
			value = BigDecimal.valueOf(doubleValue);
		} else if (valueRef instanceof Integer intValue) {
			value = BigDecimal.valueOf(intValue);
		} else if (valueRef instanceof String strValue) {
			if (strValue.startsWith(VARIABLE_PREFIX)) {
				String variableName = strValue.substring(VARIABLE_PREFIX.length());
				value = variables.get(variableName);
			} else {
				throw new UnsupportedOperationException("Not supported String: " + valueRef);
			}
		} else {
			throw new UnsupportedOperationException("Not supported: " + valueRef);
		}
		return value;
	}

	private static Value<BigDecimal> createValue(Variables variables, String valueRef) {
		Value<BigDecimal> value;
		if (valueRef.startsWith(VARIABLE_PREFIX)) {
			String variableName = valueRef.substring(1);
			value = variables.valueOf(variableName);
		} else {
			try {
				BigDecimal number = new BigDecimal(valueRef);
				value = () -> number;
			} catch (NumberFormatException cause) {
				throw new UnsupportedOperationException("Not supported: " + valueRef, cause);
			}
		}
		return value;
	}

	private static Graph.Instance createGraph(Graph.Model graphModel, Graph.Instance.Validator.Aggregator graphValidator, Calculation.Factory calculationFactory, Predicate<Graph.Model.ID> displayedLotPredicate, String mergedName, Collection<String> resourceKeys) {
		var data = new Object() {
			Graph.Instance graph;
			boolean idMergerIsCalled;
		};

		// Create complete graph
		data.graph = graphModel.instantiate();
		graphValidator.validate(data.graph);

		// Identify lots to merge because not displayed
		List<Graph.Model.ID> lotsToMerge = data.graph.vertices()//
				.map(Graph.Instance.Node::id)//
				.filter(id -> id.value().startsWith("Lot."))//
				.filter(not(displayedLotPredicate))//
				.toList();

		// Establish naming rules for merged nodes
		Function<List<Graph.Model.ID>, Graph.Model.ID> idMerger = ids -> {
			List<String> prefixes = ids.stream()//
					.map(Main2::idPrefix)//
					.distinct()//
					.toList();
			int count = prefixes.size();
			if (count != 1) {
				throw new UnsupportedOperationException("Currently support exactly 1 prefix at a time, not this: " + prefixes);
			}
			return new Graph.Model.ID(prefixes.get(0) + "." + mergedName);
		};

		// Merge lots that should not be displayed
		if (!lotsToMerge.isEmpty()) {
			data.graph = mergeNodes(data.graph, calculationFactory, lotsToMerge, idMerger.apply(lotsToMerge), resourceKeys);
			graphValidator.validate(data.graph);

			// Merge links and nodes irrelevant for displayed lots
			do {
				data.graph = reduceLinks(data.graph, calculationFactory);
				graphValidator.validate(data.graph);

				data.idMergerIsCalled = false;
				data.graph = reduceNodes(data.graph, calculationFactory, uponCalling(idMerger, (ids, mergedId) -> data.idMergerIsCalled = true), resourceKeys);
				graphValidator.validate(data.graph);
			} while (data.idMergerIsCalled);
		}

		return data.graph;
	}

	private static Function<List<Graph.Model.ID>, Graph.Model.ID> uponCalling(Function<List<Graph.Model.ID>, Graph.Model.ID> idMerger, BiConsumer<List<Graph.Model.ID>, Graph.Model.ID> callback) {
		return ids -> {
			Graph.Model.ID mergedId = idMerger.apply(ids);
			callback.accept(ids, mergedId);
			return mergedId;
		};
	}

	private static Graph.Instance reduceNodes(Graph.Instance graph, Calculation.Factory calc, Function<List<Graph.Model.ID>, Graph.Model.ID> idMerger, Collection<String> resourceKeys) {
		var data = new Object() {
			Graph.Instance graph;
		};
		data.graph = graph;

		graph.edges()//
				// Map each source ID to all its targets ID
				.collect(groupingBy(link -> link.source().id(), mapping(link -> link.target().id(), toList())))//
				.entrySet().stream()//

				// Retain only the sources having 1 target
				// TODO Generalize?
				.filter(entry -> entry.getValue().size() == 1)//

				// Map source to unique target
				.map(entry -> Map.entry(entry.getKey(), entry.getValue().get(0)))

				// Map target to sources grouped by source prefix
				.collect(groupingBy(Map.Entry::getValue, groupingBy(entry -> idPrefix(entry.getKey()), mapping(Map.Entry::getKey, toList()))))//

				// Stream source groups
				.entrySet().stream()//
				.flatMap(groups -> groups.getValue().entrySet().stream()) //
				.map(Map.Entry::getValue) //

				// Retain only groups having several sources
				.filter(sources -> sources.size() > 1)

				// Reduce source nodes per group
				.forEach(sources -> data.graph = mergeNodes(data.graph, calc, sources, idMerger.apply(sources), resourceKeys));

		return data.graph;
	}

	private static String idPrefix(Graph.Model.ID id) {
		String value = id.value();
		int prefixEnd = value.lastIndexOf(".");
		return value.substring(0, prefixEnd);
	}

	private static Graph.Instance mergeNodes(Graph.Instance graph, Calculation.Factory calc, Collection<Graph.Model.ID> mergingNodeIds, Graph.Model.ID mergedNodeId, Collection<String> resourceKeys) {
		LOGGER.accept("Merging as " + mergedNodeId.value() + " from " + mergingNodeIds.stream().map(Graph.Model.ID::value).toList());

		var data = new Object() {
			Collection<Graph.Instance.Node> newNodes = new LinkedList<>();
			Collection<Graph.Instance.Link> newLinks = new LinkedList<>();
			Calculation.Resources mergedResources = Calculation.Resources.createEmpty();
			Collection<Graph.Instance.Link> absolutedLinks = new LinkedList<>();
		};

		graph.vertices().forEach(node -> {
			if (mergingNodeIds.contains(node.id())) {
				data.mergedResources = data.mergedResources.add(node.resources());
			} else {
				data.newNodes.add(node);
			}
		});
		Graph.Instance.Node mergedNode = Graph.Instance.Node.create(mergedNodeId, data.mergedResources);
		data.newNodes.add(mergedNode);

		graph.edges().forEach(link -> {
			if (mergingNodeIds.contains(link.target().id())) {
				data.absolutedLinks.add(new Graph.Instance.Link(link.source(), calc.absolute(link.calculation(), link.source().resources()), mergedNode));
			} else if (mergingNodeIds.contains(link.source().id())) {
				data.absolutedLinks.add(new Graph.Instance.Link(mergedNode, calc.absolute(link.calculation(), link.source().resources()), link.target()));
			} else {
				data.newLinks.add(link);
			}
		});

		Collection<Graph.Instance.Link> reducedAbsolutedLinks = reduceLinks(data.absolutedLinks, calc);

		reducedAbsolutedLinks.stream()//
				.map(link -> {
					try {
						return new Graph.Instance.Link(link.source(), calc.relativize(link.calculation(), link.source().resources()), link.target());
					} catch (Exception cause) {
						throw new RuntimeException("Fail to relativize " + link.source().id().value() + " -> " + link.target().id().value(), cause);
					}
				})//
				.forEach(data.newLinks::add);

		return new Graph.Instance(data.newNodes::stream, data.newLinks::stream);
	}

	private static Graph.Instance reduceLinks(Graph.Instance graph, Calculation.Factory calc) {
		return new Graph.Instance(graph::vertices, reduceLinks(graph.edges().toList(), calc)::stream);
	}

	private static Collection<Graph.Instance.Link> reduceLinks(Collection<Graph.Instance.Link> links, Calculation.Factory calc) {
		record Pair(Graph.Instance.Node source, Graph.Instance.Node target) {
			Pair(Graph.Instance.Link link) {
				this(link.source(), link.target());
			}
		}
		var data = new Object() {
			Collection<Graph.Instance.Link> newLinks = new LinkedList<>(links);
		};
		links.stream()//
				.map(Pair::new)// Retain only (source, target) pairs
				.collect(groupingBy(Function.identity(), counting()))// count pairs
				.entrySet().stream()// Stream on (pair, count)
				.filter(entry -> entry.getValue() > 1)// Keep only non-unique pairs
				.map(Map.Entry::getKey)// Focus on pairs themselves
				.forEach(pair -> {// Merge links for each non-unique pair
					Graph.Instance.Node source = pair.source();
					Graph.Instance.Node target = pair.target();

					// Extract and add calculations relating to pair links
					Iterator<Graph.Instance.Link> iterator = data.newLinks.iterator();
					var data2 = new Object() {
						Calculation aggregatedCalculation = null;
					};
					while (iterator.hasNext()) {
						Graph.Instance.Link link = iterator.next();
						if (link.source().equals(source) && link.target().equals(target)) {
							iterator.remove();
							if (data2.aggregatedCalculation == null) {
								data2.aggregatedCalculation = link.calculation();
							} else {
								data2.aggregatedCalculation = calc.aggregate(data2.aggregatedCalculation, link.calculation());
							}
						} else {
							// Keep link as is
						}
					}

					// Create aggregated link
					data.newLinks.add(new Graph.Instance.Link(source, data2.aggregatedCalculation, target));
				});

		return data.newLinks;
	}

	public static BiConsumer<BigDecimal, IllegalArgumentException> isBetween(BigDecimal min, BigDecimal max) {
		return isAtLeast(min).andThen(isAtMost(max));
	}

	public static BiConsumer<BigDecimal, IllegalArgumentException> isAtLeast(BigDecimal min) {
		return (value, cause) -> {
			if (value.compareTo(min) < 0) {
				throw new IllegalStateException(value + " is below " + min, cause);
			}
		};
	}

	public static BiConsumer<BigDecimal, IllegalArgumentException> isAtMost(BigDecimal max) {
		return (value, cause) -> {
			if (value.compareTo(max) > 0) {
				throw new IllegalStateException(value + " is above " + max, cause);
			}
		};
	}

	public static Consumer<BigDecimal> sumsUpTo(BigDecimal goal) {
		IllegalStateException cause = new IllegalStateException("Must sum up to: " + goal);
		return (total) -> {
			if (!isPracticallyZero(total.subtract(goal))) {
				throw new IllegalStateException("Incorrect total: " + total, cause);
			}
		};
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

			default Resources subtract(Resources subtracted) {
				return subtracted.multiply(new BigDecimal("-1")).add(this);
			}

			default Resources multiply(BigDecimal multiplier) {
				return resourceKey -> resource(resourceKey).map(value -> value.multiply(multiplier));
			}

			static Resources createEmpty() {
				return resourceKey -> Optional.empty();
			}
		}

		static interface Factory {
			Calculation absolute(Calculation calculation, Calculation.Resources source);

			Calculation relativize(Calculation calculation, Calculation.Resources source);

			Calculation aggregate(Calculation calculation1, Calculation calculation2);

			Calculation resource(String resourceKey, Value<BigDecimal> value);

			default Calculation resource(String resourceKey, BigDecimal value) {
				return resource(resourceKey, () -> value);
			}

			Calculation everything();

			Group createGroup();

			static interface Group {
				Calculation part(Value<BigDecimal> value);

				default Calculation part(BigDecimal value) {
					return part(() -> value);
				}

				default Calculation part(BigInteger value) {
					return part(() -> new BigDecimal(value));
				}

				void constrainsValue(BiConsumer<BigDecimal, IllegalArgumentException> validator);

				void constrainsTotal(Consumer<BigDecimal> validator);
			}

			static class Base implements Factory {

				private final Graph.Instance.Validator.Aggregator graphValidator;

				public Base(Graph.Instance.Validator.Aggregator graphValidator) {
					this.graphValidator = graphValidator;
				}

				@Override
				public Calculation absolute(Calculation calculation, Resources source) {
					throw new UnsupportedOperationException();
				}

				@Override
				public Calculation relativize(Calculation calculation, Resources source) {
					throw new UnsupportedOperationException();
				}

				@Override
				public Calculation aggregate(Calculation calculation1, Calculation calculation2) {
					throw new UnsupportedOperationException();
				}

				@Override
				public Calculation resource(String resourceKey, Value<BigDecimal> value) {
					requireNonNull(resourceKey);
					IllegalArgumentException cause = new IllegalArgumentException("Invalid value");
					var validator = isAtLeast(BigDecimal.ZERO);
					return source -> {
						requireNonNull(source);
						validator.accept(value.get(), cause);
						BigDecimal ref = source.resource(resourceKey).orElseThrow(() -> new IllegalArgumentException("No " + resourceKey + " in " + source));
						BigDecimal ratio = ComputationUtil.divide(value.get(), ref);
						return source.multiply(ratio);
					};
				}

				@Override
				public Calculation everything() {
					return source -> source;
				}

				@Override
				public Group createGroup() {
					var data = new Object() {
						Supplier<BigDecimal> totalSupplier = () -> BigDecimal.ZERO;
						Map<IllegalArgumentException, Value<BigDecimal>> values = new HashMap<>();
					};
					Group group = new Group() {
						@Override
						public Calculation part(Value<BigDecimal> value) {
							IllegalArgumentException potentialInvalidCause = new IllegalArgumentException("Invalid value");
							data.values.put(potentialInvalidCause, value);
							Supplier<BigDecimal> partSupplier = () -> value.get();
							Supplier<BigDecimal> oldTotalSupplier = data.totalSupplier;
							data.totalSupplier = () -> oldTotalSupplier.get().add(partSupplier.get());
							return source -> {
								BigDecimal partValue = partSupplier.get();
								BigDecimal totalValue = data.totalSupplier.get();
								BigDecimal ratio = ComputationUtil.divide(partValue, totalValue);
								return source.multiply(ratio);
							};
						}

						@Override
						public void constrainsValue(BiConsumer<BigDecimal, IllegalArgumentException> validator) {
							graphValidator.addValidator((graph, resourceKeys) -> {
								data.values.forEach((cause, value) -> {
									validator.accept(value.get(), cause);
								});
							});
						}

						@Override
						public void constrainsTotal(Consumer<BigDecimal> validator) {
							graphValidator.addValidator((graph, resourceKeys) -> {
								validator.accept(data.totalSupplier.get());
							});
						}
					};

					IllegalStateException exception = new IllegalStateException("No value in group " + group);
					graphValidator.addValidator((graph, resourceKeys) -> {
						if (isPracticallyZero(data.totalSupplier.get())) {
							throw exception;
						}
					});

					return group;
				}
			}
		}
	}

	private static interface Graph<V, E> {
		Stream<V> vertices();

		Stream<E> edges();

		static class Model implements Graph<Model.ID, Model.Relation> {
			private final Collection<ID> ids = new LinkedHashSet<>();
			private final Collection<Relation> relations = new LinkedList<>();
			private final Map<Model.ID, Map<String, BigDecimal>> inputs = new HashMap<>();

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

			void assign(String source, String key, BigDecimal value) {
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

			public Instance instantiate() {
				Map<Model.ID, Instance.Node> nodes = new HashMap<>();

				// Create input nodes
				Collection<Model.ID> inputIds = inputs.keySet();
				inputIds.stream().forEach(id -> {
					requireNonNull(id);
					Map<String, BigDecimal> values = inputs.get(id);
					requireNonNull(values);
					nodes.put(id, Instance.Node.create(id, resourceKey -> {
						requireNonNull(resourceKey);
						return Optional.ofNullable(values.get(resourceKey));
					}));
				});

				// Create non-input nodes
				ids.stream()//
						.filter(id -> !inputIds.contains(id))//
						.sorted(idComparatorAsPerRelations(relations))//
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
											Calculation.Resources sourceResources = source.resources()::resource;

											Calculation calculation = relation.calculation();
											Calculation.Resources resource = calculation.compute(sourceResources);
											wrapper.targetResources = wrapper.targetResources.add(resource);
										});
								return wrapper.targetResources;
							});
							nodes.put(targetId, Graph.Instance.Node.create(targetId, resourceKey -> proxy.get().resource(resourceKey)));
						});

				// Create links
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

				return new Instance(nodes.values()::stream, links::stream);
			}

			private Comparator<Model.ID> idComparatorAsPerRelations(Collection<Model.Relation> relations) {
				Map<Model.ID, Set<Model.ID>> orderedIds = relations.stream().collect(groupingBy(Model.Relation::source, mapping(Model.Relation::target, toSet())));
				BiPredicate<Model.ID, Model.ID> isBefore = (id1, id2) -> {
					Set<Model.ID> ids = Set.of(id1);
					while (true) {
						Set<Model.ID> nexts = ids.stream().map(orderedIds::get).filter(not(Objects::isNull)).reduce(new HashSet<>(), (s1, s2) -> {
							s1.addAll(s2);
							return s1;
						});
						if (nexts.contains(id1)) {
							throw new IllegalStateException("Loop on " + id1);
						}
						if (nexts.isEmpty()) {
							return false;
						}
						// FIXME Relation comparator ineffective with this
						// Why does it work without it?
						// Why with this the comparator contract is broken?
//						if (nexts.contains(id2)) {
//							return true;
//						}
						ids = nexts;
					}
				};
				Comparator<Model.ID> idValueComparator = Comparator.comparing(id -> {
					String value = id.value();
					// FIXME All that stuff should be useless if relation comparator works properly
					int prefixEnd = value.lastIndexOf(".") + 1;
					String prefix = value.substring(0, prefixEnd);
					String end = value.substring(prefixEnd);
					String val;
					try {
						val = String.format(prefix + "%09d", Integer.parseInt(end));
					} catch (NumberFormatException cause) {
						val = value;
					}
					return val;
				});
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

			private final Supplier<Stream<Node>> nodes;
			private final Supplier<Stream<Link>> links;

			public Instance(Supplier<Stream<Node>> nodes, Supplier<Stream<Link>> links) {
				this.nodes = nodes;
				this.links = links;
			}

			@Override
			public Stream<Node> vertices() {
				return nodes.get();
			}

			@Override
			public Stream<Link> edges() {
				return links.get();
			}

			static interface Node {
				Model.ID id();

				Optional<Double> get(String resourceKey);

				Calculation.Resources resources();

				static Node create(Model.ID id, Calculation.Resources resources) {
					return new Node() {
						@Override
						public Model.ID id() {
							return id;
						}

						@Override
						public Calculation.Resources resources() {
							return resources;
						}

						@Override
						public Optional<Double> get(String resourceKey) {
							return resources.resource(resourceKey).map(BigDecimal::doubleValue);
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

			static interface Validator {
				void validate(Instance graph, Collection<String> resourceKeys);

				public static Validator checkResourcesFromRootsAmountToResourcesFromLeaves() {
					return (graph, resourceKeys) -> {
						Collection<Instance.Node> nonRootNodes = graph.edges().map(Instance.Link::target).toList();
						Calculation.Resources rootResources = graph.vertices()//
								.filter(node -> !nonRootNodes.contains(node))//
								.map(Instance.Node::resources)//
								.reduce(Calculation.Resources.createEmpty(), Calculation.Resources::add);

						Collection<Instance.Node> nonLeafNodes = graph.edges().map(Instance.Link::source).toList();
						Calculation.Resources leafResources = graph.vertices()//
								.filter(node -> !nonLeafNodes.contains(node))//
								.map(Instance.Node::resources)//
								.reduce(Calculation.Resources.createEmpty(), Calculation.Resources::add);

						Calculation.Resources diffResources = rootResources.subtract(leafResources);
						for (String resourceKey : resourceKeys) {
							BigDecimal resourceValue = diffResources.resource(resourceKey).orElse(BigDecimal.ZERO);
							if (!isPracticallyZero(resourceValue)) {
								BigDecimal rootResource = rootResources.resource(resourceKey).orElse(BigDecimal.ZERO);
								BigDecimal leafResource = leafResources.resource(resourceKey).orElse(BigDecimal.ZERO);
								throw new IllegalStateException("Root resource " + resourceKey + " (" + rootResource + ") not properly consumed (" + leafResource + "), diff: " + resourceValue);
							}
						}
					};
				}

				public static Validator checkLinksFullyConsumeTheirSourceNode() {
					return (graph, resourceKeys) -> {
						graph.edges()//
								.map(Instance.Link::source)//
								.forEach(sourceNode -> {
									Calculation.Resources sourceResources = sourceNode.resources();

									Calculation.Resources consumedResources = graph.edges()//
											.filter(link -> link.source().equals(sourceNode))//
											.map(Instance.Link::calculation)//
											.map(calc -> calc.compute(sourceResources))//
											.reduce(Calculation.Resources.createEmpty(), Calculation.Resources::add);

									Calculation.Resources remainingResources = sourceResources.subtract(consumedResources);
									for (String resourceKey : resourceKeys) {
										BigDecimal resourceValue = remainingResources.resource(resourceKey).orElse(BigDecimal.ZERO);
										if (!isPracticallyZero(resourceValue)) {
											BigDecimal sourceResource = sourceResources.resource(resourceKey).orElse(BigDecimal.ZERO);
											BigDecimal consumedResource = consumedResources.resource(resourceKey).orElse(BigDecimal.ZERO);
											throw new IllegalStateException("Node " + sourceNode.id() + " " + resourceKey + " (" + sourceResource + ") not properly consumed (" + consumedResource + "), diff: " + resourceValue);
										}
									}
								});
					};
				}

				public static Validator checkLinksFullyFeedTheirTargetNode() {
					return (graph, resourceKeys) -> {
						graph.edges()//
								.map(Instance.Link::target)//
								.forEach(targetNode -> {
									Calculation.Resources targetResources = targetNode.resources();

									Calculation.Resources fedResources = graph.edges()//
											.filter(link -> link.target().equals(targetNode))//
											.map(link -> link.calculation().compute(link.source().resources()))//
											.reduce(Calculation.Resources.createEmpty(), Calculation.Resources::add);

									Calculation.Resources diffResources = targetResources.subtract(fedResources);
									for (String resourceKey : resourceKeys) {
										BigDecimal resourceValue = diffResources.resource(resourceKey).orElse(BigDecimal.ZERO);
										if (!isPracticallyZero(resourceValue)) {
											BigDecimal targetResource = targetResources.resource(resourceKey).orElse(BigDecimal.ZERO);
											BigDecimal fedResource = fedResources.resource(resourceKey).orElse(BigDecimal.ZERO);
											throw new IllegalStateException("Node " + targetNode.id() + " " + resourceKey + " (" + targetResource + ") not properly fed (" + fedResource + "), diff: " + resourceValue);
										}
									}
								});
					};
				}

				static class Aggregator {

					private final Collection<String> resourceKeys;
					private final Collection<Validator> validators = new LinkedList<>();

					public Aggregator(Collection<String> resourceKeys) {
						this.resourceKeys = resourceKeys;
					}

					private void addValidator(Validator validator) {
						validators.add(validator);
					}

					public void validate(Instance graph) {
						for (Validator validator : validators) {
							validator.validate(graph, resourceKeys);
						}
					}
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
		Map<String, BigDecimal> values = new HashMap<>();

		public BigDecimal get(String variable) {
			return values.compute(variable, (key, value) -> {
				if (value == null) {
					throw new IllegalArgumentException("No value for variable: " + variable);
				}
				return value;
			});
		}

		public void set(String variable, BigDecimal value) {
			values.compute(variable, (key, oldValue) -> {
				if (oldValue != null) {
					throw new IllegalArgumentException("Variable " + variable + " set with " + oldValue + ", cannot set: " + value);
				}
				return value;
			});
		}

		public Value<BigDecimal> valueOf(String variable) {
			return () -> get(variable);
		}
	}

	static class ComputationUtil {
		private static final int COMPUTATION_SCALE = 25;
		private static final int ERROR_SCALE = 20;
		private static final BigDecimal ERROR_MARGIN = new BigDecimal(BigInteger.ONE, ERROR_SCALE);

		public static BigDecimal divide(BigDecimal dividend, BigDecimal divisor) {
			return dividend.divide(divisor, COMPUTATION_SCALE, RoundingMode.HALF_EVEN);
		}

		public static boolean isPracticallyZero(BigDecimal value) {
			return value.abs().compareTo(ERROR_MARGIN) <= 0;
		}
	}

	record Data(Map<String, Map<String, Calculation>> defined, Map<String, Group> groups) {
	}
}
