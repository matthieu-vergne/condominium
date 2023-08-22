package fr.vergne.condominium.core.history;

import static java.time.format.DateTimeFormatter.ISO_LOCAL_DATE;
import static java.time.format.DateTimeFormatter.ISO_LOCAL_TIME;
import static java.util.Collections.unmodifiableSet;
import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.counting;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.file.Path;
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

import fr.vergne.condominium.core.Profile;
import fr.vergne.condominium.core.mail.Mail;
import fr.vergne.condominium.core.parser.yaml.Filter;
import fr.vergne.condominium.core.parser.yaml.ProfilesConfiguration;
import net.sourceforge.plantuml.FileFormat;
import net.sourceforge.plantuml.FileFormatOption;
import net.sourceforge.plantuml.SourceStringReader;

public interface MailHistory {
	void writeSvg(Path svgPath);

	public interface Factory {
		MailHistory create(List<Mail> mails);

		public static class WithPlantUml implements Factory {

			private final Consumer<Mail.Address> profilesFeeder;
			private final Supplier<Collection<Profile>> profilesSupplier;
			private final UnaryOperator<Collection<Profile>> profilesReducer;
			private final String newline = "\\n";// Escaped to be interpreted by PlantUml
			private final Consumer<String> logger;
			private final Filter.PredicateFactory<Profile> filterPredicateFactory = Filter.PredicateFactory
					.createFromCollections(Profile::names, Profile::emails);

			public WithPlantUml(ProfilesConfiguration confProfiles, Consumer<String> logger) {
				this.logger = logger;

				Map<String, Profile> emailProfiles = new HashMap<>();
				confProfiles.getIndividuals().stream().forEach(confProfile -> {
					Set<String> names = new LinkedHashSet<>(confProfile.getNames());
					Set<String> emails = new LinkedHashSet<>(confProfile.getEmails());
					Profile.Base profile = new Profile.Base(names, emails);
					emails.forEach(email -> {
						emailProfiles.put(email, profile);
					});
				});
				this.profilesFeeder = address -> {
					Set<String> names = new LinkedHashSet<>();
					address.name().ifPresent(names::add);
					Set<String> emails = new LinkedHashSet<>();
					emails.add(address.email());
					emailProfiles.compute(address.email(), (k, v) -> {
						if (v != null) {
							names.addAll(v.names());
							emails.addAll(v.emails());
						}
						return new Profile.Base(unmodifiableSet(names), unmodifiableSet(emails));
					});
				};
				this.profilesSupplier = () -> {
					return new LinkedHashSet<>(emailProfiles.values());
				};

				UnaryOperator<Collection<Profile>> cleaning = combine(createCleaningReducers());
				UnaryOperator<Collection<Profile>> grouping = combine(createGroupsReducers(confProfiles));
				this.profilesReducer = cleaning.andThen(grouping)::apply;
			}

			@Override
			public MailHistory create(List<Mail> mails) {
				return new MailHistory() {

					@Override
					public void writeSvg(Path svgPath) {
						logger.accept("Prepare profiles");
						Collection<Profile> profiles = createProfiles(mails);

						logger.accept("Redact script");
						String script = createPlantUml(profiles, mails);

						logger.accept("Generate SVG");
						SourceStringReader reader = new SourceStringReader(script);
						FileOutputStream graphStream;
						try {
							graphStream = new FileOutputStream(svgPath.toFile());
						} catch (FileNotFoundException cause) {
							throw new RuntimeException("Cannot find: " + svgPath, cause);
						}
						String desc;
						try {
							desc = reader.outputImage(graphStream, new FileFormatOption(FileFormat.SVG))
									.getDescription();
						} catch (IOException cause) {
							throw new RuntimeException("Cannot write SVG", cause);
						}
						logger.accept(desc);
					}
				};
			}

			private Collection<Profile> createProfiles(List<Mail> mails) {
				mails.stream()//
						.forEach(mail -> {
							profilesFeeder.accept(mail.sender());
							mail.receivers().forEach(profilesFeeder);
//							mail.lines().forEach(System.out::println);
						});

				Collection<Profile> profiles = profilesReducer.apply(profilesSupplier.get());

				return profiles;
			}

			private UnaryOperator<Collection<Profile>> combine(
					Stream<UnaryOperator<Collection<Profile>>> profilesReducers) {
				return profilesReducers.reduce((op1, op2) -> op1.andThen(op2)::apply).get();
			}

			record Actor(Profile profile, String id) {
				record Activity(long sent, long received) {
					long total() {
						return sent + received;
					}
				}
			}

			private String createPlantUml(Collection<Profile> profiles, List<Mail> mails) {
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

			private Map<Actor, Actor.Activity> measureActorsActivity(Collection<Actor> actors, List<Mail> mails,
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

			private void writeActors(Collection<Actor> actors, Map<Actor, Actor.Activity> actorsActivity,
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
							String addressesStr = Stream.concat(nameStream, emailsStream).collect(joining(newline));

							String description = activityStr + newline + addressesStr;

							scriptStream.println("actor \"" + description + "\" as " + actor.id());
						});
			}

			private Collection<Actor> createActors(Collection<Profile> profiles) {
				int[] count = { 0 };
				return profiles.stream()//
						.map(profile -> new Actor(profile, "Actor" + count[0]++))//
						.toList();
			}

			private void writeSequence(List<Mail> mails, Function<Mail.Address, Actor> addressToActor,
					PrintStream scriptStream) {
				DateTimeFormatter timeFormatter = new DateTimeFormatterBuilder()//
						.append(ISO_LOCAL_DATE).appendLiteral(" ").append(ISO_LOCAL_TIME)//
						.toFormatter(Locale.getDefault());

				int subjectLengthLimit = 100;
				UnaryOperator<String> subjectAdapter = subject -> {
					return subject.substring(0, Math.min(subjectLengthLimit, subject.length()));
				};
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

			private Stream<UnaryOperator<Collection<Profile>>> createGroupsReducers(
					ProfilesConfiguration profilesConf) {
				Map<String, ProfilesConfiguration.Group> groups = profilesConf.getGroups();
				return groups.entrySet().stream().map(entry -> {
					String name = entry.getKey();
					Predicate<Profile> profilePredicate = filterPredicateFactory
							.createPredicate(entry.getValue().getFilter());
					return createReducerByProfile(name, profilePredicate);
				});
			}

			private Stream<UnaryOperator<Collection<Profile>>> createCleaningReducers() {
				return Stream.of(//
						createProfilesReducerByCommonEmail(), //
						createProfileNamesCleaner()//
				);
			}

			private UnaryOperator<Collection<Profile>> createProfilesReducerByCommonEmail() {
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

			private UnaryOperator<Collection<Profile>> createProfileNamesCleaner() {
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

			private UnaryOperator<Collection<Profile>> createReducerByProfile(String globalName,
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

			private <T> UnaryOperator<Collection<T>> createSinglePassReducer(UnaryOperator<T> pivotInitializer,
					BiPredicate<T, T> mergePredicate, BiConsumer<T, T> merger,
					Function<T, Optional<T>> pivotFinalizer) {
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

			private <T> UnaryOperator<Collection<T>> createSinglePassReducer(Supplier<T> pivotInitializer,
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
	}
}
