package fr.vergne.condominium.core.diagram;

import java.util.LinkedHashMap;
import java.util.function.Function;
import java.util.function.Predicate;

import org.jfree.data.xy.XYSeries;

import fr.vergne.condominium.core.mail.Mail;

public interface SeriesSelector extends Function<Mail, XYSeries> {
	public static Builder builder() {
		return new SeriesSelector.Builder() {
			LinkedHashMap<Predicate<Mail>, XYSeries> seriesSelectors = new LinkedHashMap<>();

			@Override
			public SeriesUser when(Predicate<Mail> mailPredicate) {
				return series -> seriesSelectors.put(mailPredicate, series);
			}

			@Override
			public SeriesSelector build() {
				return mail -> seriesSelectors.entrySet().stream()//
						.filter(entry -> entry.getKey().test(mail))//
						.map(entry -> entry.getValue())//
						.findFirst().orElseThrow(() -> new IllegalArgumentException("No series for mail: " + mail));
			}
		};
	}

	interface SeriesUser {
		void use(XYSeries series);
	}

	interface Builder {
		SeriesUser when(Predicate<Mail> mailPredicate);

		default SeriesUser whenNoOther() {
			return when(mail -> true);
		}

		SeriesSelector build();
	}
}
