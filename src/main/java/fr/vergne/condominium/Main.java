package fr.vergne.condominium;

import static java.time.format.DateTimeFormatter.ISO_LOCAL_DATE;
import static java.time.format.DateTimeFormatter.ISO_LOCAL_TIME;
import static java.util.Collections.unmodifiableSet;
import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.counting;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

import fr.vergne.condominium.core.Profile;
import fr.vergne.condominium.core.diagram.DiagramFactory;
import fr.vergne.condominium.core.mail.Header;
import fr.vergne.condominium.core.mail.Mail;
import fr.vergne.condominium.core.parser.mbox.MBoxParser;
import fr.vergne.condominium.core.parser.yaml.MailCleaningConfiguration;
import fr.vergne.condominium.core.parser.yaml.MailCleaningConfiguration.Exclusion;
import fr.vergne.condominium.core.parser.yaml.PlotConfiguration;
import fr.vergne.condominium.core.parser.yaml.ProfilesConfiguration;
import net.sourceforge.plantuml.FileFormat;
import net.sourceforge.plantuml.FileFormatOption;
import net.sourceforge.plantuml.SourceStringReader;

public class Main {

	public static void main(String[] args) throws IOException {
		Path folderPath = Paths.get(args[0]);
		Path mboxPath = folderPath.resolve(args[1]);
		Path confProfilesPath = folderPath.resolve("confProfiles.yaml");
		Path confMailCleaningPath = folderPath.resolve("confMailCleaning.yaml");
		Path confPlotCsPath = folderPath.resolve("confPlotCs.yaml");
		Path confPlotSyndicPath = folderPath.resolve("confPlotSyndic.yaml");
		Path historyPath = folderPath.resolve("graph.svg");
		Path plotCsPath = folderPath.resolve("graph2.png");
		Path plotSyndicPath = folderPath.resolve("graph3.png");

		var store = new Object() {
			Map<String, Profile> profiles = new HashMap<>();
		};

		ProfilesConfiguration confProfiles = ProfilesConfiguration.parser().apply(confProfilesPath);

		confProfiles.getIndividuals().stream().forEach(confProfile -> {
			Set<String> names = new LinkedHashSet<>(confProfile.getNames());
			Set<String> emails = new LinkedHashSet<>(confProfile.getEmails());
			Profile.Base profile = new Profile.Base(names, emails);
			emails.forEach(email -> {
				store.profiles.put(email, profile);
			});
		});

		Consumer<Mail.Address> profileFeeder = address -> {
			Set<String> names = new LinkedHashSet<>();
			address.name().ifPresent(names::add);
			Set<String> emails = new LinkedHashSet<>();
			emails.add(address.email());
			store.profiles.compute(address.email(), (k, v) -> {
				if (v != null) {
					names.addAll(v.names());
					emails.addAll(v.emails());
				}
				return new Profile.Base(unmodifiableSet(names), unmodifiableSet(emails));
			});
		};
		MBoxParser parser = new MBoxParser();
		int[] count = { 0 };

		MailCleaningConfiguration confMailCleaning = MailCleaningConfiguration.parser().apply(confMailCleaningPath);
		List<Mail> mails = parser.parseMBox(mboxPath)//
				.peek(mail -> {
					++count[0];
					System.out.println(count[0] + "> " + mail.lines().get(0));
				})//
				.filter(on(confMailCleaning))//
				.peek(mail -> {
					profileFeeder.accept(mail.sender());
					mail.receivers().forEach(profileFeeder);
//					mail.lines().forEach(System.out::println);
				})//
				.collect(toList());

		System.out.println("=================");

		UnaryOperator<Collection<Profile>> cleaningReducers = combine(
				createCleaningReducers().map(withDiffDisplay(System.out::println)));
		{
			Collection<Profile> oldProfiles = new LinkedHashSet<>(store.profiles.values());
			Collection<Profile> reducedProfiles = cleaningReducers.apply(oldProfiles);
			Map<String, Profile> newProfiles = indexByEmail(reducedProfiles);
			store.profiles = newProfiles;
			System.out.println("Reduced: " + oldProfiles.size() + " -> " + reducedProfiles.size());
		}

		UnaryOperator<Collection<Profile>> groupsReducer = combine(
				createGroupsReducers(confProfiles).map(withDiffDisplay(System.out::println)));
		Collection<Profile> reducedProfiles;
		{
			Collection<Profile> oldProfiles = new LinkedHashSet<>(store.profiles.values());
			reducedProfiles = groupsReducer.apply(oldProfiles);
			Map<String, Profile> newProfiles = indexByEmail(reducedProfiles);
			store.profiles = newProfiles;
			System.out.println("Reduced: " + oldProfiles.size() + " -> " + reducedProfiles.size());
		}

		System.out.println("=================");
		// TODO Remove profiles?
		// TODO Extract in dedicated objects
		writeMailHistory(mails, reducedProfiles, historyPath);

		System.out.println("=================");
		int diagramWidth = 1000;
		int diagramHeightPerPlot = 250;
		DiagramFactory diagramFactory = new DiagramFactory(confProfiles.getGroups(), diagramWidth,
				diagramHeightPerPlot);
		{
			System.out.println("Read CS plot conf");
			PlotConfiguration confPlotCs = PlotConfiguration.parser().apply(confPlotCsPath);
			System.out.println("Create plot");
			BufferedImage plotCs = diagramFactory.createSendReceiveDiagram(confPlotCs, mails);
			ImageIO.write(plotCs, "PNG", plotCsPath.toFile());
			System.out.println("Done");
		}
		{
			System.out.println("Read syndic plot conf");
			PlotConfiguration confPlotSyndic = PlotConfiguration.parser().apply(confPlotSyndicPath);
			System.out.println("Create plot");
			BufferedImage plotSyndic = diagramFactory.createSendReceiveDiagram(confPlotSyndic, mails);
			ImageIO.write(plotSyndic, "PNG", plotSyndicPath.toFile());
			System.out.println("Done");
		}
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

	private static Function<? super UnaryOperator<Collection<Profile>>, ? extends UnaryOperator<Collection<Profile>>> withDiffDisplay(
			Consumer<Object> displayer) {
		return reducer -> (UnaryOperator<Collection<Profile>>) previousProfiles -> {
			Collection<Profile> nextProfiles = reducer.apply(previousProfiles);
			displayer.accept("<<<<<");
			previousProfiles.stream().filter(profile -> !nextProfiles.contains(profile)).forEach(displayer);
			displayer.accept("-----");
			nextProfiles.stream().filter(profile -> !previousProfiles.contains(profile)).forEach(displayer);
			displayer.accept(">>>>>");
			return nextProfiles;
		};
	}

	private static UnaryOperator<Collection<Profile>> combine(
			Stream<UnaryOperator<Collection<Profile>>> profilesReducers) {
		return profilesReducers.reduce((op1, op2) -> op1.andThen(op2)::apply).get();
	}

	private static void writeMailHistory(List<Mail> mails, Collection<Profile> profiles, Path svgPath)
			throws FileNotFoundException, IOException {
		System.out.println("Redact script");
		String script = createPlantUml(mails, profiles);
		System.out.println("Generate SVG");
		SourceStringReader reader = new SourceStringReader(script);
		FileOutputStream graphStream = new FileOutputStream(svgPath.toFile());
		String desc = reader.outputImage(graphStream, new FileFormatOption(FileFormat.SVG)).getDescription();
		System.out.println(desc);
		System.out.println("Done");
	}

	record Actor(Profile profile, String id) {
		record Activity(long sent, long received) {
			long total() {
				return sent + received;
			}
		}
	}

	private static String createPlantUml(List<Mail> mails, Collection<Profile> profiles) {
		Collection<Actor> actors = createActors(profiles);
		Function<Mail.Address, Actor> addressToActor = address -> {
			return actors.stream().filter(actor -> actor.profile().contains(address)).findFirst().get();
		};
		Map<Actor, Actor.Activity> actorsActivity = measureActorsActivity(actors, mails, addressToActor);

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		PrintStream scriptStream = new PrintStream(out, false, Charset.forName("UTF-8"));
		scriptStream.println("@startuml");
		scriptStream.println("hide unlinked");
		writeActors(actors, actorsActivity, scriptStream);
		writeSequence(mails, addressToActor, scriptStream);
		scriptStream.println("@enduml");
		scriptStream.flush();
		String script = out.toString();

		return script;
	}

	private static Map<Actor, Actor.Activity> measureActorsActivity(Collection<Actor> actors, List<Mail> mails,
			Function<Mail.Address, Actor> addressToActor) {
		Map<Actor, Long> actorSent = mails.stream()//
				.map(Mail::sender)//
				.map(addressToActor)//
				.collect(groupingBy(actor -> actor, counting()));

		Map<Actor, Long> actorReceived = mails.stream()//
				.flatMap(mail -> {
					Mail.Address senderAddress = mail.sender();
					Actor senderActor = addressToActor.apply(senderAddress);
					return mail.receivers()//
							.map(addressToActor)//
							// Ignore communications within same actor
							// Apply to both same and different addresses
							.filter(actor -> !actor.equals(senderActor))//
							// Several addresses might be in the same actor
							// Count each actor once
							.distinct();
				})//
				.collect(groupingBy(actor -> actor, counting()));

		return actors.stream().collect(toMap(//
				actor -> actor, //
				actor -> {
					long sent = actorSent.getOrDefault(actor, 0L);
					long received = actorReceived.getOrDefault(actor, 0L);
					return new Actor.Activity(sent, received);
				}//
		));
	}

	private static void writeActors(Collection<Actor> actors, Map<Actor, Actor.Activity> actorsActivity,
			PrintStream scriptStream) {
		actors.stream()//
				.sorted(comparing(actor -> actorsActivity.get(actor).total()).reversed())//
				.forEach(actor -> {
					Actor.Activity activity = actorsActivity.get(actor);
					long sent = activity.sent();
					long received = activity.received();
					String activityStr = "[" + sent + " → " + received + "]";

					Stream<String> nameStream = actor.profile().names().stream()//
							.findFirst().map(Stream::of).orElse(Stream.empty());
					Stream<String> emailsStream = actor.profile().emails().stream()//
							.map(String::toLowerCase).distinct().sorted();
					// TODO Factor newline
					String newline = "\\n";// Escaped to be interpreted by PlantUml
					String addressesStr = Stream.concat(nameStream, emailsStream).collect(joining(newline));

					String description = activityStr + newline + addressesStr;

					scriptStream.println("actor \"" + description + "\" as " + actor.id());
				});
	}

	private static Collection<Actor> createActors(Collection<Profile> profiles) {
		int[] count = { 0 };
		return profiles.stream()//
				.map(profile -> new Actor(profile, "Actor" + count[0]++))//
				.toList();
	}

	private static void writeSequence(List<Mail> mails, Function<Mail.Address, Actor> addressToActor,
			PrintStream scriptStream) {
		DateTimeFormatter timeFormatter = new DateTimeFormatterBuilder()//
				.append(ISO_LOCAL_DATE).appendLiteral(" ").append(ISO_LOCAL_TIME)//
				.toFormatter(Locale.getDefault());

		int subjectLengthLimit = 100;
		UnaryOperator<String> subjectAdapter = subject -> {
			return subject.substring(0, Math.min(subjectLengthLimit, subject.length()));
		};
		// TODO Factor newline
		String newline = "\\n";// Escaped to be interpreted by PlantUml
		mails.stream()//
				.sorted(comparing(Mail::receivedDate))//
				.forEach(mail -> {
					Mail.Address senderAddress = mail.sender();
					Actor senderActor = addressToActor.apply(senderAddress);
					String senderId = senderActor.id();
					ZonedDateTime date = mail.receivedDate();
					String dateStr = timeFormatter.format(date);
					mail.receivers().map(addressToActor)//
							// Ignore communications within same actor
							// Apply to both same and different addresses
							.filter(receiverActor -> !receiverActor.equals(senderActor))//
							// Several addresses might be in the same actor
							// Count each actor once
							.distinct()//

							.forEach(receiverActor -> {
								String receiverId = receiverActor.id();
								String subject = subjectAdapter.apply(mail.subject());
								String description = dateStr + newline + subject;
								scriptStream.println(senderId + " --> " + receiverId + " : " + description);
							});
				});
	}

	private static Map<String, Profile> indexByEmail(Collection<Profile> reducedProfiles) {
		Map<String, Profile> newProfiles = new HashMap<>();
		reducedProfiles.forEach(profile -> {
			profile.emails().forEach(email -> {
				Profile previous = newProfiles.put(email, profile);
				if (previous != null) {
					throw new IllegalStateException(
							"Same email " + email + " used for several profiles: " + previous + " and " + profile);
				}
			});
		});
		return newProfiles;
	}

	private static Stream<UnaryOperator<Collection<Profile>>> createGroupsReducers(ProfilesConfiguration profilesConf) {
		Map<String, ProfilesConfiguration.Group> groups = profilesConf.getGroups();
		return groups.entrySet().stream().map(entry -> {
			String name = entry.getKey();
			Predicate<Profile> profilePredicate = entry.getValue().getFilter().toProfilePredicate();
			return createReducerByProfile(name, profilePredicate);
		});
	}

	private static Stream<UnaryOperator<Collection<Profile>>> createCleaningReducers() {
		return Stream.of(//
				createProfilesReducerByCommonEmail(), //
				createProfileNamesCleaner()//
		);
	}

	private static UnaryOperator<Collection<Profile>> createProfilesReducerByCommonEmail() {
		return createSinglePassReducer(//
				(profile) -> new Profile.Base(new HashSet<>(profile.names()), new HashSet<>(profile.emails())), //
				(pivot, profile) -> profile.emails().stream().map(String::toLowerCase)//
						.anyMatch(pivot.emails().stream().map(String::toLowerCase).collect(toList())::contains), //
				(pivot, profile) -> {
					pivot.names().addAll(profile.names());
					pivot.emails().addAll(profile.emails());
				}, //
				(pivot) -> Optional
						.of(new Profile.Base(unmodifiableSet(pivot.names()), unmodifiableSet(pivot.emails())))//
		);
	}

	private static UnaryOperator<Collection<Profile>> createProfileNamesCleaner() {
		return profiles -> {
			return profiles.stream()//
					.map(profile -> {
						Set<String> names = new LinkedHashSet<>(profile.names());
						Set<String> emails = profile.emails();
						names.removeAll(emails);
						return new Profile.Base(unmodifiableSet(names), emails);
					})//
					.collect(toList());
		};
	}

	private static UnaryOperator<Collection<Profile>> createReducerByProfile(String globalName,
			Predicate<Profile> profilePredicate) {
		return createSinglePassReducer(//
				() -> new Profile.Base(new LinkedHashSet<>(), new LinkedHashSet<>()), //
				profilePredicate, //
				(pivot, profile) -> {
					pivot.names().addAll(profile.names());
					pivot.emails().addAll(profile.emails());
				}, //
				(pivot) -> {
					Set<String> emails = pivot.emails();
					if (emails.isEmpty()) {
						return Optional.empty();
					} else {
						Set<String> names = new LinkedHashSet<>();
						names.add(globalName);
						names.addAll(pivot.names());
						return Optional.of(new Profile.Base(unmodifiableSet(names), unmodifiableSet(emails)));
					}
				}//
		);
	}

	public static <T> UnaryOperator<Collection<T>> createIterativeReducer(UnaryOperator<T> pivotInitializer,
			BiPredicate<T, T> mergePredicate, BiConsumer<T, T> merger, Function<T, Optional<T>> pivotFinalizer) {
		UnaryOperator<Collection<T>> singlePassReducer = createSinglePassReducer(pivotInitializer, mergePredicate,
				merger, pivotFinalizer);
		return new UnaryOperator<Collection<T>>() {

			@Override
			public Collection<T> apply(Collection<T> items) {
				Collection<T> remainingItems = items;
				boolean hasReduced = false;
				do {
					Collection<T> reducedItems = singlePassReducer.apply(remainingItems);
					hasReduced = reducedItems.size() < remainingItems.size();
					remainingItems = reducedItems;
				} while (hasReduced);
				return remainingItems;
			}
		};
	}

	public static <T> UnaryOperator<Collection<T>> createSinglePassReducer(UnaryOperator<T> pivotInitializer,
			BiPredicate<T, T> mergePredicate, BiConsumer<T, T> merger, Function<T, Optional<T>> pivotFinalizer) {
		return new UnaryOperator<Collection<T>>() {

			@Override
			public Collection<T> apply(Collection<T> items) {
				Collection<T> result = new LinkedList<>();
				Collection<T> processingItems = items;
				while (!processingItems.isEmpty()) {
					Collection<T> remainingItems = new LinkedList<>();
					var wrapper = new Object() {
						T pivot = null;
					};
					processingItems.forEach(item -> {
						if (wrapper.pivot == null) {
							wrapper.pivot = pivotInitializer.apply(item);
						} else if (mergePredicate.test(wrapper.pivot, item)) {
							merger.accept(wrapper.pivot, item);
						} else {
							remainingItems.add(item);
						}
					});
					pivotFinalizer.apply(wrapper.pivot).ifPresent(result::add);
					processingItems = remainingItems;
				}
				return result;
			}
		};
	}

	public static <T> UnaryOperator<Collection<T>> createSinglePassReducer(Supplier<T> pivotInitializer,
			Predicate<T> mergePredicate, BiConsumer<T, T> merger, Function<T, Optional<T>> pivotFinalizer) {
		return new UnaryOperator<Collection<T>>() {

			@Override
			public Collection<T> apply(Collection<T> items) {
				Collection<T> result = new LinkedList<>();
				var wrapper = new Object() {
					T pivot = pivotInitializer.get();
				};
				items.forEach(item -> {
					if (mergePredicate.test(item)) {
						merger.accept(wrapper.pivot, item);
					} else {
						result.add(item);
					}
				});
				pivotFinalizer.apply(wrapper.pivot).ifPresent(result::add);
				return result;
			}
		};
	}
}
