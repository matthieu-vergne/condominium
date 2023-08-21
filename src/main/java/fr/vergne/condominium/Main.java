package fr.vergne.condominium;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

import fr.vergne.condominium.core.diagram.Diagram;
import fr.vergne.condominium.core.history.MailHistory;
import fr.vergne.condominium.core.mail.Header;
import fr.vergne.condominium.core.mail.Mail;
import fr.vergne.condominium.core.parser.mbox.MBoxParser;
import fr.vergne.condominium.core.parser.yaml.MailCleaningConfiguration;
import fr.vergne.condominium.core.parser.yaml.PlotConfiguration;
import fr.vergne.condominium.core.parser.yaml.ProfilesConfiguration;

public class Main {
	private static final Consumer<String> logger = System.out::println;

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

		MBoxParser parser = new MBoxParser();
		MailCleaningConfiguration confMailCleaning = MailCleaningConfiguration.parser().apply(confMailCleaningPath);
		List<Mail> mails = parser.parseMBox(mboxPath)//
				.peek(displayMail())//
				.filter(on(confMailCleaning))//
				.toList();

		logger.accept("=================");

		logger.accept("Read profiles conf");
		ProfilesConfiguration confProfiles = ProfilesConfiguration.parser().apply(confProfilesPath);

		logger.accept("=================");
		{
			// TODO Filter on mail predicate
			logger.accept("Create mail history");
			MailHistory.Factory mailHistoryFactory = new MailHistory.Factory.WithPlantUml(confProfiles, logger);
			MailHistory mailHistory = mailHistoryFactory.create(mails);
			mailHistory.writeSvg(historyPath);
			logger.accept("Done");
		}

		logger.accept("=================");
		int diagramWidth = 1000;
		int diagramHeightPerPlot = 250;
		Diagram.Factory diagramFactory = new Diagram.Factory.WithJFreeChart(confProfiles.getGroups());
		{
			logger.accept("Read CS plot conf");
			PlotConfiguration confPlotCs = PlotConfiguration.parser().apply(confPlotCsPath);
			logger.accept("Create plot");
			Diagram diagram = diagramFactory.ofSendReceive(confPlotCs).createDiagram(mails);
			diagram.writePng(plotCsPath, diagramWidth, diagramHeightPerPlot);
			logger.accept("Done");
		}
		{
			logger.accept("Read syndic plot conf");
			PlotConfiguration confPlotSyndic = PlotConfiguration.parser().apply(confPlotSyndicPath);
			logger.accept("Create plot");
			Diagram diagram = diagramFactory.ofSendReceive(confPlotSyndic).createDiagram(mails);
			diagram.writePng(plotSyndicPath, diagramWidth, diagramHeightPerPlot);
			logger.accept("Done");
		}
	}

	private static Consumer<Mail> displayMail() {
		int[] count = { 0 };
		return mail -> {
			++count[0];
			logger.accept(count[0] + "> " + mail.lines().get(0));
		};
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

}
