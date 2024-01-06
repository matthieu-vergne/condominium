package fr.vergne.condominium.gui;

import java.awt.Rectangle;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;

import fr.vergne.condominium.core.util.Persister;

public class Configuration {
	private final Properties properties = new Properties();

	public Configuration() {
	}

	// FIXME Remove
	@Deprecated
	public Properties properties() {
		return properties;
	}

	private static class GuiBoundsKey {
		private static final String X = "x";
		private static final String Y = "y";
		private static final String WIDTH = "width";
		private static final String HEIGHT = "height";
	}

	public void setGuiBounds(Rectangle bounds) {
		setPropertyInt(GuiBoundsKey.X, bounds.x);
		setPropertyInt(GuiBoundsKey.Y, bounds.y);
		setPropertyInt(GuiBoundsKey.WIDTH, bounds.width);
		setPropertyInt(GuiBoundsKey.HEIGHT, bounds.height);
	}

	public Optional<Rectangle> guiBounds() {
		return propertyInt(GuiBoundsKey.X).flatMap(x -> //
		propertyInt(GuiBoundsKey.Y).flatMap(y -> //
		propertyInt(GuiBoundsKey.WIDTH).flatMap(width -> //
		propertyInt(GuiBoundsKey.HEIGHT).flatMap(height -> {
			return Optional.of(new Rectangle(x, y, width, height));
		}))));
	}

	private Optional<Integer> propertyInt(String key) {
		return Optional.ofNullable(properties.getProperty(key)).map(Integer::parseInt);
	}

	private Object setPropertyInt(String key, int value) {
		return properties.setProperty(key, "" + value);
	}

	public Persister persistInFile(Path confPath) {
		return new Persister() {
			@Override
			public boolean hasSave() {
				return Files.exists(confPath);
			}

			@Override
			public void save() {
				try {
					properties.store(Files.newBufferedWriter(confPath), null);
				} catch (IOException cause) {
					throw new RuntimeException("Cannot write: " + confPath, cause);
				}
			}

			@Override
			public void load() {
				try {
					properties.load(Files.newBufferedReader(confPath));
				} catch (IOException cause) {
					throw new RuntimeException("Cannot read: " + confPath, cause);
				}
			}
		};
	}
}
