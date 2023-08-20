package fr.vergne.condominium.core.diagram;

import static java.util.stream.Collectors.counting;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toMap;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.Supplier;

import org.jfree.data.xy.DefaultTableXYDataset;
import org.jfree.data.xy.XYDataset;
import org.jfree.data.xy.XYSeries;

import fr.vergne.condominium.core.mail.Mail;
import fr.vergne.condominium.core.parser.yaml.ProfilesConfiguration;
import fr.vergne.condominium.core.util.Pair;

public interface DatasetsBuilder {
	void feed(Collection<Mail> mails);

	LinkedHashMap<String, XYDataset> build();

	public static DatasetsBuilder create(Map<String, ProfilesConfiguration.Group> confGroups, String defaultGroup,
			LinkedHashMap<String, Pair<String>> subplotGroups, String noGroupName) {
		Function<String, NamedAddressPredicate> nameToPredicate = createAddressPredicateFactory(confGroups,
				defaultGroup);
		SeriesSelector.Builder seriesSelectorBuilder = SeriesSelector.builder();
		List<DatasetFactory> datasetFactories = new LinkedList<>();
		subplotGroups.entrySet().stream()//
				.forEach(entry -> {
					Pair<String> namesPair = entry.getValue();
					Pair<NamedAddressPredicate> predicatesPair = namesPair.map(nameToPredicate);
					NamedAddressPredicate predicate1 = predicatesPair.a();
					NamedAddressPredicate predicate2 = predicatesPair.b();

					// Series
					String name1 = predicate1.name();
					String name2 = predicate2.name();
					boolean autoSort = true;
					boolean allowDuplicateXValues = false;
					XYSeries sentSeries = new XYSeries(name1 + " → " + name2, autoSort, allowDuplicateXValues);
					XYSeries receivedSeries = new XYSeries(name1 + " ← " + name2, autoSort, allowDuplicateXValues);

					// Series selector
					seriesSelectorBuilder
							.when(mail -> predicate1.test(mail.sender()) && mail.receivers().anyMatch(predicate2))//
							.use(sentSeries);
					seriesSelectorBuilder
							.when(mail -> predicate2.test(mail.sender()) && mail.receivers().anyMatch(predicate1))//
							.use(receivedSeries);

					// Dataset factory
					String subplotTitle = entry.getKey();
					datasetFactories.add(DatasetFactory.create(subplotTitle, () -> {
						DefaultTableXYDataset dataset = new DefaultTableXYDataset();
						dataset.addSeries(sentSeries);
						dataset.addSeries(receivedSeries);
						return dataset;
					}));
				});
		{
			// Series
			XYSeries remainingSeries = new XYSeries(noGroupName + " → " + noGroupName, true, false);

			// Series selector
			seriesSelectorBuilder.whenNoOther().use(remainingSeries);

			// Dataset factory
			datasetFactories.add(DatasetFactory.create(noGroupName, () -> {
				DefaultTableXYDataset dataset = new DefaultTableXYDataset();
				dataset.addSeries(remainingSeries);
				return dataset;
			}));
		}

		return new DatasetsBuilder() {
			private final SeriesSelector seriesSelector = seriesSelectorBuilder.build();

			@Override
			public void feed(Collection<Mail> mails) {
				Map<XYSeries, Map<ZonedDateTime, Long>> seriesData = mails.stream().collect(//
						groupingBy(seriesSelector, //
								groupingBy(mail -> middleOfWeek(mail.receivedDate()), //
										counting())));

				List<ZonedDateTime> allDates = seriesData.values().stream()//
						.flatMap(entry -> entry.keySet().stream())//
						.sorted().distinct().toList();

				seriesData.entrySet().forEach(entry -> {
					XYSeries series = entry.getKey();
					Map<ZonedDateTime, Long> dateCounts = entry.getValue();
					allDates.forEach(date -> {
						long count = dateCounts.getOrDefault(date, 0L);
						series.add(toEpochMillis(date), count);
					});
				});
			}

			@Override
			public LinkedHashMap<String, XYDataset> build() {
				return datasetFactories.stream().collect(toMap(//
						DatasetFactory::name, //
						DatasetFactory::createDataset, //
						noMerger(), //
						LinkedHashMap::new//
				));
			}
		};
	}

	interface DatasetFactory {
		String name();

		XYDataset createDataset();

		public static DatasetFactory create(String name, Supplier<XYDataset> datasetSupplier) {
			return new DatasetFactory() {

				@Override
				public String name() {
					return name;
				}

				@Override
				public XYDataset createDataset() {
					return datasetSupplier.get();
				}
			};
		}
	}

	private static Function<String, NamedAddressPredicate> createAddressPredicateFactory(
			Map<String, ProfilesConfiguration.Group> confGroups, String defaultGroup) {
		return name -> {
			if (name.equals(defaultGroup)) {
				return NamedAddressPredicate.anyAs(defaultGroup);
			}
			ProfilesConfiguration.Group group = confGroups.get(name);
			if (group == null) {
				throw new IllegalStateException("No group: " + name);
			}
			return NamedAddressPredicate.create(name, group.getFilter().toAddressPredicate());
		};
	}

	private static ZonedDateTime middleOfWeek(ZonedDateTime dateTime) {
		ZonedDateTime startOfDay = dateTime.minusNanos(dateTime.toLocalTime().toNanoOfDay());
		ZonedDateTime middleOfWeek = startOfDay.minusDays(dateTime.getDayOfWeek().getValue() - 4);
		return middleOfWeek;
	}

	private static long toEpochMillis(ZonedDateTime dateTime) {
		return dateTime.toInstant().toEpochMilli();
	}

	private static <T> BinaryOperator<T> noMerger() {
		return (a, b) -> {
			throw new RuntimeException("No merge needed");
		};
	}

}
