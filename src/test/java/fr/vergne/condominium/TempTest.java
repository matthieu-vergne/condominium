package fr.vergne.condominium;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;

// FIXME Replace by unit tests on actual components
class TempTest {
	Path srcFolder = Paths.get(System.getProperty("srcFolder"));
	Path refFolder = Paths.get(System.getProperty("refFolder"));

	@Test
	void testPlantUmlScript() throws IOException {
		String fileName = "graph.plantuml";
		Path srcPath = srcFolder.resolve(fileName);
		Path refPath = refFolder.resolve(fileName);
		byte[] src = Files.readAllBytes(srcPath);
		byte[] ref = Files.readAllBytes(refPath);
		assertThat(src, is(equalTo(ref)));
	}

	@Test
	void testPlantUmlImage() throws IOException {
		String fileName = "graph.svg";
		Path srcPath = srcFolder.resolve(fileName);
		Path refPath = refFolder.resolve(fileName);
		byte[] src = Files.readAllBytes(srcPath);
		byte[] ref = Files.readAllBytes(refPath);
		assertThat(src, is(equalTo(ref)));
	}

	@Test
	void testPlantUmlScriptCharges() throws IOException {
		String fileName = "graphCharges.plantuml";
		Path srcPath = srcFolder.resolve(fileName);
		Path refPath = refFolder.resolve(fileName);
		String src = new String(Files.readAllBytes(srcPath));
		String ref = new String(Files.readAllBytes(refPath));
		assertThat(src, is(equalTo(ref)));
	}

	@Test
	void testPlantUmlChargesImage() throws IOException {
		String fileName = "graphCharges.svg";
		Path srcPath = srcFolder.resolve(fileName);
		Path refPath = refFolder.resolve(fileName);
		String src = new String(Files.readAllBytes(srcPath));
		String ref = new String(Files.readAllBytes(refPath));
		assertThat(src, is(equalTo(ref)));
	}

	@Test
	void testGraph2() throws IOException {
		String fileName = "graph2.png";
		Path srcPath = srcFolder.resolve(fileName);
		Path refPath = refFolder.resolve(fileName);
		byte[] src = Files.readAllBytes(srcPath);
		byte[] ref = Files.readAllBytes(refPath);
		assertThat(src, is(equalTo(ref)));
	}

	@Test
	void testGraph3() throws IOException {
		String fileName = "graph3.png";
		Path srcPath = srcFolder.resolve(fileName);
		Path refPath = refFolder.resolve(fileName);
		byte[] src = Files.readAllBytes(srcPath);
		byte[] ref = Files.readAllBytes(refPath);
		assertThat(src, is(equalTo(ref)));
	}
}
