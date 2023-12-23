package fr.vergne.condominium.core.parser.yaml;

import java.util.function.Function;

import fr.vergne.condominium.core.monitorable.Monitorable;
import fr.vergne.condominium.core.monitorable.Question;
import fr.vergne.condominium.core.source.Source;
import fr.vergne.condominium.core.source.Source.Refiner;
import fr.vergne.condominium.core.util.RefinerIdSerializer;
import fr.vergne.condominium.core.util.Serializer;

public interface QuestionYamlSerializer {

	public static Serializer<Question, String> create(Function<Source<?>, Source.Track> sourceTracker,
			Serializer<Source<?>, String> sourceSerializer, Serializer<Refiner<?, ?, ?>, String> refinerSerializer,
			RefinerIdSerializer refinerIdSerializer) {
		Class<Question> monitorableClass = Question.class;
		Monitorable.Factory<Question, Question.State> monitorableFactory = Question::create;
		Serializer<Question.State, String> stateSerializer = new Serializer<Question.State, String>() {

			@Override
			public String serialize(Question.State state) {
				return state.name().toLowerCase();
			}

			@Override
			public Question.State deserialize(String serial) {
				return Question.State.valueOf(serial.toUpperCase());
			}
		};
		return MonitorableYamlSerializer.create(monitorableClass, monitorableFactory, stateSerializer, sourceTracker,
				sourceSerializer, refinerSerializer, refinerIdSerializer);
	}
}
