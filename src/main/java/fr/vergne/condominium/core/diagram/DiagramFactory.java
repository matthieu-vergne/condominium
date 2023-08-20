package fr.vergne.condominium.core.diagram;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.function.Supplier;

import org.jfree.chart.JFreeChart;
import org.jfree.chart.LegendItem;
import org.jfree.chart.LegendItemCollection;
import org.jfree.chart.axis.DateAxis;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.axis.ValueAxis;
import org.jfree.chart.plot.CombinedDomainXYPlot;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.StandardXYItemRenderer;
import org.jfree.chart.renderer.xy.XYItemRenderer;
import org.jfree.data.xy.XYDataset;

import fr.vergne.condominium.core.mail.Mail;
import fr.vergne.condominium.core.parser.yaml.PlotConfiguration;
import fr.vergne.condominium.core.parser.yaml.ProfilesConfiguration;

public class DiagramFactory {
	private final Map<String, ProfilesConfiguration.Group> confGroups;
	private final int diagramWidth;
	private final int diagramHeightPerPlot;

	public DiagramFactory(Map<String, ProfilesConfiguration.Group> confGroups, int diagramWidth, int diagramHeightPerPlot) {
		this.confGroups = confGroups;
		this.diagramWidth = diagramWidth;
		this.diagramHeightPerPlot = diagramHeightPerPlot;
	}

	public BufferedImage createSendReceiveDiagram(PlotConfiguration confPlotCs, List<Mail> mails) {
		DatasetsBuilder datasetsBuilder = DatasetsBuilder.create(//
				confGroups, //
				confPlotCs.getDefaultGroup(), //
				confPlotCs.getSubplots(), //
				confPlotCs.getNoGroupName()//
		);

		datasetsBuilder.feed(mails);
		LinkedHashMap<String, XYDataset> datasets = datasetsBuilder.build();

		List<XYPlot> subplots = createSubplots(datasets);

		String chartTitle = confPlotCs.getTitle();
		JFreeChart chart = createChart(chartTitle, subplots);

		return chart.createBufferedImage(diagramWidth, diagramHeightPerPlot * subplots.size());
	}

	private static JFreeChart createChart(String chartTitle, List<XYPlot> subplots) {
		CombinedDomainXYPlot plot = createPlot(subplots);

		JFreeChart chart = new JFreeChart(plot);
		chart.setTitle(chartTitle);
		return chart;
	}

	private static CombinedDomainXYPlot createPlot(List<XYPlot> subplots) {
		CombinedDomainXYPlot plot = new CombinedDomainXYPlot(
				// TODO Factor title with code producing dates
				new DateAxis("Semaines", TimeZone.getDefault(), Locale.getDefault()));
		
		subplots.forEach(plot::add);

		LegendItemCollection legendItems = new LegendItemCollection();
		@SuppressWarnings("unchecked")
		Iterator<LegendItem> iterator = plot.getLegendItems().iterator();
		// TODO Factor legend items with renderer colors
		legendItems.add(new LegendItem("Envoyé", iterator.next().getFillPaint()));
		legendItems.add(new LegendItem("Reçu", iterator.next().getFillPaint()));
		plot.setFixedLegendItems(legendItems);
		
		return plot;
	}

	private static List<XYPlot> createSubplots(LinkedHashMap<String, XYDataset> datasets) {
		Supplier<XYItemRenderer> rendererFactory = () -> {
			StandardXYItemRenderer renderer = new StandardXYItemRenderer();
			// TODO Factor renderer colors with legend items
			renderer.setSeriesPaint(0, Color.RED);
			renderer.setSeriesPaint(1, Color.BLUE);
			return renderer;
		};
		var wrapper = new Object() {
			double lowerRange = Double.MAX_VALUE;
			double upperRange = Double.MIN_VALUE;
		};
		List<XYPlot> subplots = datasets//
				.entrySet().stream()//
				.map(entry -> {
					String label = entry.getKey();
					XYDataset dataset = entry.getValue();
					ValueAxis rangeAxis = new NumberAxis(label);
					XYPlot subplot = new XYPlot(dataset, null, rangeAxis, rendererFactory.get());
					wrapper.lowerRange = Math.min(wrapper.lowerRange, rangeAxis.getLowerBound());
					wrapper.upperRange = Math.max(wrapper.upperRange, rangeAxis.getUpperBound());
					return subplot;
				})//
				.toList();
		for (XYPlot subplot : subplots) {
			ValueAxis rangeAxis = subplot.getRangeAxis();
			rangeAxis.setLowerBound(wrapper.lowerRange);
			rangeAxis.setUpperBound(wrapper.upperRange);
		}
		return subplots;
	}
}
