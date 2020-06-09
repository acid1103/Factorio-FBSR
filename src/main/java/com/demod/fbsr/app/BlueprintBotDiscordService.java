package com.demod.fbsr.app;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.net.URL;
import java.time.Instant;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.commons.text.similarity.LevenshteinDistance;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.luaj.vm2.LuaValue;

import com.demod.dcba.CommandHandler;
import com.demod.dcba.CommandHandler.NoArgHandler;
import com.demod.dcba.DCBA;
import com.demod.dcba.DiscordBot;
import com.demod.factorio.Config;
import com.demod.factorio.DataTable;
import com.demod.factorio.FactorioData;
import com.demod.factorio.Utils;
import com.demod.factorio.prototype.DataPrototype;
import com.demod.fbsr.Blueprint;
import com.demod.fbsr.BlueprintEntity;
import com.demod.fbsr.BlueprintFinder;
import com.demod.fbsr.BlueprintStringData;
import com.demod.fbsr.FBSR;
import com.demod.fbsr.RenderUtils;
import com.demod.fbsr.TaskReporting;
import com.demod.fbsr.TaskReporting.Level;
import com.demod.fbsr.WebUtils;
import com.demod.fbsr.WorldMap;
import com.demod.fbsr.app.WatchdogService.WatchdogReporter;
import com.google.common.collect.LinkedHashMultiset;
import com.google.common.collect.Multiset;
import com.google.common.util.concurrent.AbstractIdleService;

import net.dv8tion.jda.core.EmbedBuilder;
import net.dv8tion.jda.core.Permission;
import net.dv8tion.jda.core.entities.Message;
import net.dv8tion.jda.core.entities.MessageChannel;
import net.dv8tion.jda.core.entities.MessageEmbed;
import net.dv8tion.jda.core.entities.PrivateChannel;
import net.dv8tion.jda.core.entities.TextChannel;
import net.dv8tion.jda.core.events.message.MessageReceivedEvent;

public class BlueprintBotDiscordService extends AbstractIdleService {

	private static final int MADEUP_NUMBER_FROM_AROUND_5_IN_THE_MORNING = 200;

	private static final Pattern debugPattern = Pattern.compile("DEBUG:([A-Za-z0-9_]+)");

	private static final Map<String, String> upgradeBeltsEntityMapping = new HashMap<>();
	static {
		upgradeBeltsEntityMapping.put("transport-belt", "fast-transport-belt");
		upgradeBeltsEntityMapping.put("underground-belt", "fast-underground-belt");
		upgradeBeltsEntityMapping.put("splitter", "fast-splitter");
		upgradeBeltsEntityMapping.put("fast-transport-belt", "express-transport-belt");
		upgradeBeltsEntityMapping.put("fast-underground-belt", "express-underground-belt");
		upgradeBeltsEntityMapping.put("fast-splitter", "express-splitter");
	}

	private DiscordBot bot;

	private JSONObject configJson;

	private String reportingUserID;
	private String reportingChannelID;
	private String hostingChannelID;

	private CommandHandler createDataRawCommandHandler(Function<String[], Optional<LuaValue>> query) {
		return (event, args) -> {
			TaskReporting reporting = new TaskReporting();
			reporting.setContext(event.getMessage().getContentDisplay());

			try {
				if (args.length < 1) {
					event.getChannel().sendMessage("You didn't specify a path!").complete();
					return;
				}
				String key = Arrays.asList(args).stream().collect(Collectors.joining());
				String[] path = key.split("\\.");
				Optional<LuaValue> lua = query.apply(path);
				if (!lua.isPresent()) {
					event.getChannel()
							.sendMessage("I could not find a lua table for the path [`"
									+ Arrays.asList(path).stream().collect(Collectors.joining(", ")) + "`] :frowning:")
							.complete();
					return;
				}
				sendLuaDumpFile(event, "raw", key, lua.get(), reporting);
			} catch (Exception e) {
				reporting.addException(e);
			}
			sendReport(event, reporting);
		};
	}

	private MessageEmbed createExceptionReportEmbed(String author, String authorURL, TaskReporting reporting)
			throws IOException {
		List<Exception> exceptions = reporting.getExceptions();
		List<String> warnings = reporting.getWarnings();

		EmbedBuilder builder = new EmbedBuilder();
		builder.setAuthor(author, null, authorURL);
		builder.setTimestamp(Instant.now());

		Level level = reporting.getLevel();
		if (level != Level.INFO) {
			builder.setColor(level.getColor());
		}

		Multiset<String> uniqueWarnings = LinkedHashMultiset.create(warnings);
		if (!uniqueWarnings.isEmpty()) {
			builder.addField("Warnings",
					uniqueWarnings.entrySet().stream()
							.map(e -> e.getElement() + (e.getCount() > 1 ? " *(**" + e.getCount() + "** times)*" : ""))
							.collect(Collectors.joining("\n")),
					false);
		}

		Multiset<String> uniqueExceptions = LinkedHashMultiset.create();
		Optional<String> exceptionFile = Optional.empty();
		if (!exceptions.isEmpty()) {
			try (StringWriter sw = new StringWriter(); PrintWriter pw = new PrintWriter(sw)) {
				for (Exception e : exceptions) {
					if (uniqueExceptions.add(e.getClass().getSimpleName() + ": " + e.getMessage())) {
						e.printStackTrace();
						e.printStackTrace(pw);
					}
				}
				pw.flush();
				exceptionFile = Optional.of(sw.toString());
			}
		}
		if (!uniqueExceptions.isEmpty()) {
			builder.addField("Exceptions",
					uniqueExceptions.entrySet().stream()
							.map(e -> e.getElement() + (e.getCount() > 1 ? " *(**" + e.getCount() + "** times)*" : ""))
							.collect(Collectors.joining("\n")),
					false);
		}
		if (exceptionFile.isPresent()) {
			builder.addField("Stack Trace(s)",
					WebUtils.uploadToHostingService("exceptions.txt", exceptionFile.get().getBytes()).toString(),
					false);
		}

		return builder.build();
	}

	private CommandHandler createPrototypeCommandHandler(String category, Map<String, ? extends DataPrototype> map) {
		return (event, args) -> {
			String content = event.getMessage().getContentDisplay();
			TaskReporting reporting = new TaskReporting();
			reporting.setContext(content);

			try {
				if (args.length < 1) {
					event.getChannel().sendMessage("You didn't specify a " + category + " prototype name!").complete();
					return;
				}

				for (String search : args) {
					Optional<? extends DataPrototype> prototype = Optional.ofNullable(map.get(search));
					if (!prototype.isPresent()) {
						LevenshteinDistance levenshteinDistance = LevenshteinDistance.getDefaultInstance();
						List<String> suggestions = map.keySet().stream()
								.map(k -> new SimpleEntry<>(k, levenshteinDistance.apply(search, k)))
								.sorted((p1, p2) -> Integer.compare(p1.getValue(), p2.getValue())).limit(5)
								.map(p -> p.getKey()).collect(Collectors.toList());
						event.getChannel()
								.sendMessage(
										"I could not find the " + category + " prototype for `" + search
												+ "`. :frowning:\nDid you mean:\n" + suggestions.stream()
														.map(s -> "\t - " + s).collect(Collectors.joining("\n")))
								.complete();
						return;
					}

					sendLuaDumpFile(event, category, prototype.get().getName(), prototype.get().lua(), reporting);
				}
			} catch (Exception e) {
				reporting.addException(e);
			}
			sendReport(event, reporting);
		};
	}

	private MessageEmbed createReportEmbed(String author, String authorURL, TaskReporting reporting)
			throws IOException {
		Optional<String> context = reporting.getContext();
		List<Exception> exceptions = reporting.getExceptions();
		List<String> warnings = reporting.getWarnings();
		List<Entry<Optional<String>, String>> images = reporting.getImages();
		List<String> links = reporting.getLinks();
		List<String> downloads = reporting.getDownloads();
		Set<String> info = reporting.getInfo();
		Optional<Message> contextMessage = reporting.getContextMessage();
		List<Long> renderTimes = reporting.getRenderTimes();

		EmbedBuilder builder = new EmbedBuilder();
		builder.setAuthor(author, null, authorURL);
		builder.setTimestamp(Instant.now());

		Level level = reporting.getLevel();
		if (level != Level.INFO) {
			builder.setColor(level.getColor());
		}

		if (context.isPresent() && context.get().length() <= MADEUP_NUMBER_FROM_AROUND_5_IN_THE_MORNING) {
			builder.addField("Context", context.get(), false);
		} else if (context.isPresent()) {
			builder.addField("Context Link",
					WebUtils.uploadToHostingService("context.txt", context.get().getBytes()).toString(), false);
		}

		if (contextMessage.isPresent()) {
			builder.addField("Context Message", "[Message Link](" + contextMessage.get().getJumpUrl() + ")", false);
		}

		if (!links.isEmpty()) {
			builder.addField("Link(s)", links.stream().collect(Collectors.joining("\n")), false);
		}

		if (!images.isEmpty()) {
			try {
				builder.setImage(images.get(0).getValue());
			} catch (IllegalArgumentException e) {
				// Local Storage Image, can't preview!
			}
		}
		if (images.size() > 1) {
			WebUtils.addPossiblyLargeEmbedField(builder, "Additional Image(s)",
					images.stream().skip(1).map(Entry::getValue).collect(Collectors.joining("\n")), false);
		}

		if (!downloads.isEmpty()) {
			builder.addField("Download(s)", downloads.stream().collect(Collectors.joining("\n")), false);
		}

		if (!info.isEmpty()) {
			builder.addField("Info", info.stream().collect(Collectors.joining("\n")), false);
		}

		if (!renderTimes.isEmpty()) {
			builder.addField("Render Time", renderTimes.stream().mapToLong(l -> l).sum() + " ms"
					+ (renderTimes.size() > 1
							? (" [" + renderTimes.stream().map(Object::toString).collect(Collectors.joining(", "))
									+ "]")
							: ""),
					false);
		}

		Multiset<String> uniqueWarnings = LinkedHashMultiset.create(warnings);
		if (!uniqueWarnings.isEmpty()) {
			builder.addField("Warnings",
					uniqueWarnings.entrySet().stream()
							.map(e -> e.getElement() + (e.getCount() > 1 ? " *(**" + e.getCount() + "** times)*" : ""))
							.collect(Collectors.joining("\n")),
					false);
		}

		Multiset<String> uniqueExceptions = LinkedHashMultiset.create();
		Optional<String> exceptionFile = Optional.empty();
		if (!exceptions.isEmpty()) {
			try (StringWriter sw = new StringWriter(); PrintWriter pw = new PrintWriter(sw)) {
				for (Exception e : exceptions) {
					if (uniqueExceptions.add(e.getClass().getSimpleName() + ": " + e.getMessage())) {
						e.printStackTrace();
						e.printStackTrace(pw);
					}
				}
				pw.flush();
				exceptionFile = Optional.of(sw.toString());
			}
		}
		if (!uniqueExceptions.isEmpty()) {
			builder.addField("Exceptions",
					uniqueExceptions.entrySet().stream()
							.map(e -> e.getElement() + (e.getCount() > 1 ? " *(**" + e.getCount() + "** times)*" : ""))
							.collect(Collectors.joining("\n")),
					false);
		}
		if (exceptionFile.isPresent()) {
			builder.addField("Stack Trace(s)",
					WebUtils.uploadToHostingService("exceptions.txt", exceptionFile.get().getBytes()).toString(),
					false);
		}

		return builder.build();
	}

	private void findDebugOptions(TaskReporting reporting, String content) {
		Matcher matcher = debugPattern.matcher(content);
		while (matcher.find()) {
			String fieldName = matcher.group(1);
			try {
				Field field = WorldMap.Debug.class.getField(fieldName);
				if (!reporting.getDebug().isPresent()) {
					reporting.setDebug(Optional.of(new WorldMap.Debug()));
				}
				field.set(reporting.getDebug().get(), true);
				reporting.addWarning("Debug Enabled: " + fieldName);
			} catch (NoSuchFieldException e) {
				reporting.addWarning("Unknown Debug Option: " + fieldName);
			} catch (SecurityException | IllegalArgumentException | IllegalAccessException e) {
				reporting.addException(e);
			}
		}
	}

	private byte[] generateDiscordFriendlyPNGImage(BufferedImage image) {
		byte[] imageData = WebUtils.getImageData(image);
		if (imageData.length > 8000000) {
			return generateDiscordFriendlyPNGImage(
					RenderUtils.scaleImage(image, image.getWidth() / 2, image.getHeight() / 2));
		}
		return imageData;
	}

	private String getReadableAddress(MessageReceivedEvent event) {
		if (event.getGuild() == null) {
			return event.getAuthor().getName();
		} else {
			return event.getGuild().getName() + " / #" + event.getChannel().getName() + " / "
					+ event.getAuthor().getName();
		}
	}

	private void handleBlueprintBookAssembleCommand(MessageReceivedEvent event) {
		String content = event.getMessage().getContentDisplay();
		TaskReporting reporting = new TaskReporting();
		reporting.setContext(content);

		List<BlueprintStringData> blueprintStrings = BlueprintFinder.search(content, reporting);
		if (!event.getMessage().getAttachments().isEmpty()) {
			String url = event.getMessage().getAttachments().get(0).getUrl();
			reporting.addLink(url);
			blueprintStrings.addAll(BlueprintFinder.search(url, reporting));
		}

		if (!blueprintStrings.isEmpty()) {
			List<Blueprint> blueprints = blueprintStrings.stream().flatMap(bs -> bs.getBlueprints().stream())
					.collect(Collectors.toList());

			JSONObject json = new JSONObject();
			Utils.terribleHackToHaveOrderedJSONObject(json);
			JSONObject bookJson = new JSONObject();
			Utils.terribleHackToHaveOrderedJSONObject(bookJson);
			json.put("blueprint_book", bookJson);
			JSONArray blueprintsJson = new JSONArray();
			bookJson.put("blueprints", blueprintsJson);
			bookJson.put("item", "blueprint-book");
			bookJson.put("active_index", 0);

			Optional<Long> latestVersion = Optional.empty();
			int index = 0;
			for (Blueprint blueprint : blueprints) {
				blueprint.json().put("index", index);

				if (blueprint.getVersion().isPresent()) {
					if (latestVersion.isPresent()) {
						latestVersion = Optional.of(Math.max(blueprint.getVersion().get(), latestVersion.get()));
					} else {
						latestVersion = blueprint.getVersion();
					}
				}

				blueprintsJson.put(blueprint.json());

				index++;
			}

			String bookLabel = blueprintStrings.stream().filter(BlueprintStringData::isBook)
					.map(BlueprintStringData::getLabel).filter(Optional::isPresent).map(Optional::get).map(String::trim)
					.distinct().collect(Collectors.joining(" & "));
			if (!bookLabel.isEmpty()) {
				bookJson.put("label", bookLabel);
			}

			if (latestVersion.isPresent()) {
				bookJson.put("version", latestVersion.get());
			}

			try {
				reporting.addInfo("Assembled Book: " + WebUtils.uploadToHostingService("blueprintBook.txt",
						(" " + BlueprintStringData.encode(json)).getBytes()));
			} catch (Exception e) {
				reporting.addException(e);
			}

		} else {
			event.getChannel().sendMessage("I can't seem to find any blueprints. :frowning:").complete();
		}

		if (!reporting.getInfo().isEmpty()) {
			event.getChannel().sendMessage(reporting.getInfo().stream().collect(Collectors.joining("\n"))).complete();
		}

		sendReport(event, reporting);
	}

	private void handleBlueprintBookExtractCommand(MessageReceivedEvent event) {
		String content = event.getMessage().getContentDisplay();
		TaskReporting reporting = new TaskReporting();
		reporting.setContext(content);

		List<BlueprintStringData> blueprintStrings = BlueprintFinder.search(content, reporting);
		if (!event.getMessage().getAttachments().isEmpty()) {
			String url = event.getMessage().getAttachments().get(0).getUrl();
			reporting.addLink(url);
			blueprintStrings.addAll(BlueprintFinder.search(url, reporting));
		}

		List<Blueprint> blueprints = blueprintStrings.stream().flatMap(bs -> bs.getBlueprints().stream())
				.collect(Collectors.toList());
		List<Entry<URL, String>> links = new ArrayList<>();
		for (Blueprint blueprint : blueprints) {
			try {
				blueprint.json().remove("index");

				URL url = WebUtils.uploadToHostingService("blueprint.txt",
						(/*
							 * blueprint.getLabel().orElse("Blueprint String") + ": "
							 */" " + BlueprintStringData.encode(blueprint.json())).getBytes());
				links.add(new SimpleEntry<>(url, blueprint.getLabel().orElse(null)));
			} catch (Exception e) {
				reporting.addException(e);
			}
		}

		if (!links.isEmpty()) {
			// FIXME
			// try {
			//// reporting.addInfo("Extracted blueprints: "
			//// + WebUtils.uploadToBundly("Blueprints", "Provided by Blueprint Bot",
			// links));
			// } catch (IOException e) {
			try {
				sendBundlyReplacementEmbed(event.getChannel(), "Extracted Blueprints", links);
			} catch (Exception e2) {
				reporting.addException(e2);
			}
			// }
		}

		if (reporting.getBlueprintStrings().isEmpty()) {
			event.getChannel().sendMessage("I can't seem to find any blueprints. :frowning:").complete();
		}

		if (!reporting.getInfo().isEmpty()) {
			event.getChannel().sendMessage(reporting.getInfo().stream().collect(Collectors.joining("\n"))).complete();
		}

		sendReport(event, reporting);
	}

	private void handleBlueprintCommand(MessageReceivedEvent event) {
		String content = event.getMessage().getContentDisplay();
		TaskReporting reporting = new TaskReporting();
		reporting.setContext(content);
		reporting.setContextMessage(event.getMessage());
		findDebugOptions(reporting, content);
		if (!event.getMessage().getAttachments().isEmpty()) {
			String url = event.getMessage().getAttachments().get(0).getUrl();
			reporting.addLink(url);
			processBlueprints(BlueprintFinder.search(url, reporting), event, reporting);
		} else {
			processBlueprints(BlueprintFinder.search(content, reporting), event, reporting);
		}

		if (reporting.getBlueprintStrings().isEmpty()) {
			reporting.addInfo("Give me blueprint strings and I'll create images for you!");
			reporting.addInfo("Include a link to a text file to get started.");
		}

		if (!reporting.getInfo().isEmpty()) {
			event.getChannel().sendMessage(reporting.getInfo().stream().collect(Collectors.joining("\n"))).complete();
		}

		sendReport(event, reporting);
	}

	private void handleBlueprintItemsCommand(MessageReceivedEvent event) {
		DataTable table;
		try {
			table = FactorioData.getTable();
		} catch (JSONException | IOException e1) {
			throw new InternalError(e1);
		}

		String content = event.getMessage().getContentStripped();
		TaskReporting reporting = new TaskReporting();
		reporting.setContext(content);

		List<BlueprintStringData> blueprintStringDatas;
		if (!event.getMessage().getAttachments().isEmpty()) {
			String url = event.getMessage().getAttachments().get(0).getUrl();
			reporting.addLink(url);
			blueprintStringDatas = BlueprintFinder.search(url, reporting);
		} else {
			blueprintStringDatas = BlueprintFinder.search(content, reporting);
		}

		Map<String, Double> totalItems = new LinkedHashMap<>();
		for (BlueprintStringData blueprintStringData : blueprintStringDatas) {
			for (Blueprint blueprint : blueprintStringData.getBlueprints()) {
				Map<String, Double> items = FBSR.generateTotalItems(table, blueprint, reporting);
				items.forEach((k, v) -> {
					totalItems.compute(k, ($, old) -> old == null ? v : old + v);
				});
			}
		}

		if (!totalItems.isEmpty()) {
			try {
				String responseContent = totalItems.entrySet().stream()
						.sorted((e1, e2) -> e1.getKey().compareTo(e2.getKey()))
						.map(e -> table.getWikiItemName(e.getKey()) + ": " + RenderUtils.fmtDouble2(e.getValue()))
						.collect(Collectors.joining("\n"));
				String responseContentUrl = WebUtils.uploadToHostingService("items.txt", responseContent.getBytes())
						.toString();
				reporting.addLink(responseContentUrl);

				String response = "```ldif\n" + responseContent + "```";
				if (response.length() < 2000) {
					event.getChannel().sendMessage(response).complete();
				} else {
					reporting.addInfo(responseContentUrl);
				}
			} catch (IOException e) {
				reporting.addException(e);
			}
		} else {
			reporting.addInfo("I couldn't find any items!");
		}

		if (reporting.getImages().isEmpty() && reporting.getDownloads().isEmpty() && reporting.getWarnings().isEmpty()
				&& reporting.getExceptions().isEmpty() && reporting.getInfo().isEmpty()
				&& reporting.getLinks().isEmpty()) {
			if (content.split("\\s").length == 1) {
				reporting.addInfo("Give me blueprint strings and I'll count the items for you!");
				reporting.addInfo("Include a link to a text file to get started.");
			} else {
				reporting.addInfo("I can't seem to find any blueprints. :frowning:");
			}
		}

		if (!reporting.getInfo().isEmpty()) {
			event.getChannel().sendMessage(reporting.getInfo().stream().collect(Collectors.joining("\n"))).complete();
		}

		sendReport(event, reporting);
	}

	private void handleBlueprintItemsRawCommand(MessageReceivedEvent event) {
		DataTable table;
		try {
			table = FactorioData.getTable();
		} catch (JSONException | IOException e1) {
			throw new InternalError(e1);
		}

		String content = event.getMessage().getContentStripped();
		TaskReporting reporting = new TaskReporting();
		reporting.setContext(content);

		List<BlueprintStringData> blueprintStringDatas;
		if (!event.getMessage().getAttachments().isEmpty()) {
			String url = event.getMessage().getAttachments().get(0).getUrl();
			reporting.addLink(url);
			blueprintStringDatas = BlueprintFinder.search(url, reporting);
		} else {
			blueprintStringDatas = BlueprintFinder.search(content, reporting);
		}

		Map<String, Double> totalItems = new LinkedHashMap<>();
		for (BlueprintStringData blueprintStringData : blueprintStringDatas) {
			for (Blueprint blueprint : blueprintStringData.getBlueprints()) {
				Map<String, Double> items = FBSR.generateTotalItems(table, blueprint, reporting);
				items.forEach((k, v) -> {
					totalItems.compute(k, ($, old) -> old == null ? v : old + v);
				});
			}
		}

		Map<String, Double> rawItems = FBSR.generateTotalRawItems(table, table.getRecipes(), totalItems);

		if (!rawItems.isEmpty()) {
			try {
				String responseContent = rawItems.entrySet().stream()
						.sorted((e1, e2) -> e1.getKey().compareTo(e2.getKey()))
						.map(e -> table.getWikiItemName(e.getKey()) + ": " + RenderUtils.fmtDouble2(e.getValue()))
						.collect(Collectors.joining("\n"));
				String responseContentUrl = WebUtils.uploadToHostingService("raw-items.txt", responseContent.getBytes())
						.toString();
				reporting.addLink(responseContentUrl);

				String response = "```ldif\n" + responseContent + "```";
				if (response.length() < 2000) {
					event.getChannel().sendMessage(response).complete();
				} else {
					reporting.addInfo(responseContentUrl);
				}
			} catch (IOException e) {
				reporting.addException(e);
			}
		} else {
			reporting.addInfo("I couldn't find any raw items!");
		}

		if (reporting.getImages().isEmpty() && reporting.getDownloads().isEmpty() && reporting.getWarnings().isEmpty()
				&& reporting.getExceptions().isEmpty() && reporting.getInfo().isEmpty()
				&& reporting.getLinks().isEmpty()) {
			if (content.split("\\s").length == 1) {
				reporting.addInfo("Give me blueprint strings and I'll count the items for you!");
				reporting.addInfo("Include a link to a text file to get started.");
			} else {
				reporting.addInfo("I can't seem to find any blueprints. :frowning:");
			}
		}

		if (!reporting.getInfo().isEmpty()) {
			event.getChannel().sendMessage(reporting.getInfo().stream().collect(Collectors.joining("\n"))).complete();
		}

		sendReport(event, reporting);
	}

	private void handleBlueprintJsonCommand(MessageReceivedEvent event) {
		String content = event.getMessage().getContentDisplay();
		TaskReporting reporting = new TaskReporting();
		reporting.setContext(content);
		List<String> results = BlueprintFinder.searchRaw(content, reporting);
		if (!results.isEmpty()) {
			try {
				byte[] bytes = BlueprintStringData.decode(results.get(0)).toString(2).getBytes();
				if (results.size() == 1) {
					URL url = WebUtils.uploadToHostingService("blueprint.json", bytes);
					event.getChannel().sendMessage("Blueprint JSON: " + url.toString()).complete();
					reporting.addLink(url.toString());
				} else {
					try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
							ZipOutputStream zos = new ZipOutputStream(baos)) {
						for (int i = 0; i < results.size(); i++) {
							try {
								String blueprintString = results.get(i);
								zos.putNextEntry(new ZipEntry("blueprint " + (i + 1) + ".json"));
								zos.write(BlueprintStringData.decode(blueprintString).toString(2).getBytes());
							} catch (Exception e) {
								reporting.addException(e);
							}
						}
						zos.close();
						byte[] zipData = baos.toByteArray();
						try {
							Message response = event.getChannel().sendFile(zipData, "blueprint JSON files.zip", null)
									.complete();
							reporting.addDownload(response.getAttachments().get(0).getUrl());
						} catch (Exception e) {
							reporting.addInfo("Blueprint JSON Files: "
									+ WebUtils.uploadToHostingService("blueprint JSON files.zip", zipData));
						}
					} catch (IOException e) {
						reporting.addException(e);
					}
				}
			} catch (Exception e) {
				reporting.addException(e);
			}
		}

		if (reporting.getBlueprintStrings().isEmpty()) {
			event.getChannel().sendMessage("I can't seem to find any blueprints. :frowning:").complete();
		}
		sendReport(event, reporting);
	}

	private void handleBlueprintTotalsCommand(MessageReceivedEvent event) {
		DataTable table;
		try {
			table = FactorioData.getTable();
		} catch (JSONException | IOException e1) {
			throw new InternalError(e1);
		}

		String content = event.getMessage().getContentStripped();
		TaskReporting reporting = new TaskReporting();
		reporting.setContext(content);

		List<BlueprintStringData> blueprintStringDatas;
		if (!event.getMessage().getAttachments().isEmpty()) {
			String url = event.getMessage().getAttachments().get(0).getUrl();
			reporting.addLink(url);
			blueprintStringDatas = BlueprintFinder.search(url, reporting);
		} else {
			blueprintStringDatas = BlueprintFinder.search(content, reporting);
		}

		Map<String, Double> totalItems = new LinkedHashMap<>();
		for (BlueprintStringData blueprintStringData : blueprintStringDatas) {
			for (Blueprint blueprint : blueprintStringData.getBlueprints()) {
				Map<String, Double> items = FBSR.generateSummedTotalItems(table, blueprint, reporting);
				items.forEach((k, v) -> {
					totalItems.compute(k, ($, old) -> old == null ? v : old + v);
				});
			}
		}

		if (!totalItems.isEmpty()) {
			try {
				String responseContent = totalItems.entrySet().stream()
						.sorted((e1, e2) -> e1.getKey().compareTo(e2.getKey()))
						.map(e -> e.getKey() + ": " + RenderUtils.fmtDouble2(e.getValue()))
						.collect(Collectors.joining("\n"));
				String responseContentUrl = WebUtils.uploadToHostingService("items.txt", responseContent.getBytes())
						.toString();
				reporting.addLink(responseContentUrl);

				String response = "```ldif\n" + responseContent + "```";
				if (response.length() < 2000) {
					event.getChannel().sendMessage(response).complete();
				} else {
					reporting.addInfo(responseContentUrl);
				}
			} catch (IOException e) {
				reporting.addException(e);
			}
		} else {
			reporting.addInfo("I couldn't find any entities, tiles or modules!");
		}

		if (reporting.getImages().isEmpty() && reporting.getDownloads().isEmpty() && reporting.getWarnings().isEmpty()
				&& reporting.getExceptions().isEmpty() && reporting.getInfo().isEmpty()
				&& reporting.getLinks().isEmpty()) {
			if (content.split("\\s").length == 1) {
				reporting.addInfo("Give me blueprint strings and I'll count the items for you!");
				reporting.addInfo("Include a link to a text file to get started.");
			} else {
				reporting.addInfo("I can't seem to find any blueprints. :frowning:");
			}
		}

		if (!reporting.getInfo().isEmpty()) {
			event.getChannel().sendMessage(reporting.getInfo().stream().collect(Collectors.joining("\n"))).complete();
		}

		sendReport(event, reporting);
	}

	private void handleBlueprintUpgradeBeltsCommand(MessageReceivedEvent event) {
		String content = event.getMessage().getContentStripped();
		TaskReporting reporting = new TaskReporting();
		reporting.setContext(content);

		List<BlueprintStringData> blueprintStringDatas;
		if (!event.getMessage().getAttachments().isEmpty()) {
			String url = event.getMessage().getAttachments().get(0).getUrl();
			reporting.addLink(url);
			blueprintStringDatas = BlueprintFinder.search(url, reporting);
		} else {
			blueprintStringDatas = BlueprintFinder.search(content, reporting);
		}

		int upgradedCount = 0;
		for (BlueprintStringData blueprintStringData : blueprintStringDatas) {
			for (Blueprint blueprint : blueprintStringData.getBlueprints()) {
				for (BlueprintEntity blueprintEntity : blueprint.getEntities()) {
					String upgradeName = upgradeBeltsEntityMapping.get(blueprintEntity.getName());
					if (upgradeName != null) {
						blueprintEntity.json().put("name", upgradeName);
						upgradedCount++;
					}
				}
			}
		}

		if (upgradedCount > 0) {
			reporting.addInfo("Upgraded " + upgradedCount + " entities.");
			for (BlueprintStringData blueprintStringData : blueprintStringDatas) {
				try {
					reporting
							.addInfo(WebUtils
									.uploadToHostingService("blueprint.txt",
											BlueprintStringData.encode(blueprintStringData.json()).getBytes())
									.toString());
				} catch (IOException e) {
					reporting.addException(e);
				}
			}
		} else {
			reporting.addInfo("I couldn't find anything to upgrade!");
		}

		if (reporting.getImages().isEmpty() && reporting.getDownloads().isEmpty() && reporting.getWarnings().isEmpty()
				&& reporting.getExceptions().isEmpty() && reporting.getInfo().isEmpty()) {
			if (content.split("\\s").length == 1) {
				reporting.addInfo("Give me blueprint strings and I'll create upgrade the belts for you!");
				reporting.addInfo("Include a link to a text file to get started.");
			} else {
				reporting.addInfo("I can't seem to find any blueprints. :frowning:");
			}
		}

		if (!reporting.getInfo().isEmpty()) {
			event.getChannel().sendMessage(reporting.getInfo().stream().collect(Collectors.joining("\n"))).complete();
		}

		sendReport(event, reporting);
	}

	private void handleRedditCheckThingsCommand(MessageReceivedEvent event, String[] args) {
		String content = event.getMessage().getContentDisplay();
		TaskReporting reporting = new TaskReporting();
		reporting.setContext(content);

		try {
			if (args.length < 1) {
				event.getChannel().sendMessage("You didn't specify anything!").complete();
				return;
			}

			ServiceFinder.findService(BlueprintBotRedditService.class).ifPresent(s -> {
				try {
					s.processRequest(args);
					reporting.addInfo("Request successful!");
				} catch (Exception e) {
					reporting.addException(e);
				}
			});
		} catch (Exception e) {
			reporting.addException(e);
		}
		sendReport(event, reporting);
	}

	private void processBlueprints(List<BlueprintStringData> blueprintStrings, MessageReceivedEvent event,
			TaskReporting reporting) {
		for (BlueprintStringData blueprintString : blueprintStrings) {
			try {
				System.out.println("Parsing blueprints: " + blueprintString.getBlueprints().size());
				if (blueprintString.getBlueprints().size() == 1) {
					Blueprint blueprint = blueprintString.getBlueprints().get(0);
					BufferedImage image = FBSR.renderBlueprint(blueprint, reporting);
					try {
						Message message = event.getChannel()
								.sendFile(WebUtils.getImageData(image), "blueprint.png", null).complete();
						reporting.addImage(blueprint.getLabel(), message.getAttachments().get(0).getUrl());
					} catch (Exception e) {
						reporting.addInfo(WebUtils.uploadToHostingService("blueprint.png", image).toString());
					}
				} else {
					List<Entry<URL, String>> links = new ArrayList<>();
					for (Blueprint blueprint : blueprintString.getBlueprints()) {
						BufferedImage image = FBSR.renderBlueprint(blueprint, reporting);
						links.add(new SimpleEntry<>(WebUtils.uploadToHostingService("blueprint.png", image),
								blueprint.getLabel().orElse("")));
					}
					// FIXME
					// try {
					// reporting.addInfo("Blueprint Book Images: " + WebUtils
					// .uploadToBundly("Blueprint Book", "Renderings provided by Blueprint Bot",
					// links)
					// .toString());
					// } catch (IOException e) {
					try {
						sendBundlyReplacementEmbed(event.getChannel(), "Blueprint Book Images", links);
					} catch (Exception e2) {
						reporting.addException(e2);
					}
					// }
				}
			} catch (Exception e) {
				reporting.addException(e);
			}
		}
	}

	private void sendBundlyReplacementEmbed(MessageChannel channel, String title, List<Entry<URL, String>> links)
			throws IllegalStateException {
		ArrayDeque<String> linksFormatted = links.stream()
				.map(p -> (p.getValue() != null && !p.getValue().isEmpty())
						? ("[" + p.getValue() + "](" + p.getKey() + ")")
						: p.getKey().toString())
				.collect(Collectors.toCollection(ArrayDeque::new));
		while (!linksFormatted.isEmpty()) {
			EmbedBuilder builder = new EmbedBuilder();
			builder.setTitle(title, null);
			StringBuilder description = new StringBuilder();
			while (!linksFormatted.isEmpty()) {
				if (description.length() + linksFormatted.peek().length() + 1 < MessageEmbed.TEXT_MAX_LENGTH) {
					description.append(linksFormatted.pop()).append('\n');
				} else {
					break;
				}
			}
			builder.setDescription(description);
			channel.sendMessage(builder.build()).complete();
		}
	}

	private void sendLuaDumpFile(MessageReceivedEvent event, String category, String name, LuaValue lua,
			TaskReporting reporting) throws IOException {
		JSONObject json = new JSONObject();
		Utils.terribleHackToHaveOrderedJSONObject(json);
		json.put("name", name);
		json.put("category", category);
		json.put("version", FBSR.getVersion());
		json.put("data", Utils.<JSONObject>convertLuaToJson(lua));
		URL url = WebUtils.uploadToHostingService(category + "_" + name + "_dump_" + FBSR.getVersion() + ".json",
				json.toString(2).getBytes());
		event.getChannel().sendMessage(category + " " + name + " lua dump: " + url.toString()).complete();
		reporting.addLink(url.toString());
	}

	public void sendReport(MessageReceivedEvent event, TaskReporting reporting) {
		if (!reporting.getExceptions().isEmpty()) {
			event.getChannel().sendMessage(
					"There was a problem completing your request. I have contacted my programmer to fix it for you!")
					.complete();
		}

		sendReport(getReadableAddress(event), event.getAuthor().getEffectiveAvatarUrl(), reporting);
	}

	public void sendReport(String author, String authorURL, TaskReporting reporting) {
		try {
			PrivateChannel privateChannel = bot.getJDA().getUserById(reportingUserID).openPrivateChannel().complete();
			privateChannel.sendMessage(createReportEmbed(author, authorURL, reporting)).complete();
			if (!reporting.getExceptions().isEmpty()) {
				TextChannel textChannel = bot.getJDA().getTextChannelById(reportingChannelID);
				if (textChannel != null) {
					textChannel.sendMessage(createExceptionReportEmbed(author, authorURL, reporting)).complete();
				}
			}

		} catch (Exception e) {
			PrivateChannel privateChannel = bot.getJDA().getUserById(reportingUserID).openPrivateChannel().complete();
			privateChannel.sendMessage("Failed to create report!").complete();
			try (StringWriter sw = new StringWriter(); PrintWriter pw = new PrintWriter(sw)) {
				e.printStackTrace();
				e.printStackTrace(pw);
				pw.flush();
				privateChannel.sendFile(sw.toString().getBytes(), "Exception.txt", null).complete();
			} catch (IOException e1) {
				e1.printStackTrace();
			}
		}
	}

	@Override
	protected void shutDown() {
		ServiceFinder.removeService(this);
		ServiceFinder.removeService(WatchdogReporter.class);
		bot.stopAsync().awaitTerminated();
	}

	@Override
	protected void startUp() throws JSONException, IOException {
		configJson = Config.get().getJSONObject("discord");

		DataTable table = FactorioData.getTable();
		System.out.println("Factorio " + FBSR.getVersion() + " Data Loaded.");

		bot = DCBA.builder()//
				.setInfo("Blueprint Bot")//
				.withSupport(
						"Find Demod and complain to him!\nYou can find him over in the [Factorio Discord.](https://discord.gg/factorio)")//
				.withTechnology("[FBSR](https://github.com/demodude4u/Factorio-FBSR)",
						"Factorio Blueprint String Renderer")//
				.withTechnology("[FactorioDataWrapper](https://github.com/demodude4u/Java-Factorio-Data-Wrapper)",
						"Factorio Data Scraper")//
				.withCredits("Attribution", "[Factorio](https://www.factorio.com/) - Made by Wube Software")//
				.withCredits("Contributors", "Demod")//
				.withCredits("Contributors", "Bilka")//
				.withCredits("Contributors", "FactorioBlueprints")//
				.withCredits("Contributors", "acid")//
				.withCredits("Contributors", "Vilsol")//
				.withInvite(new Permission[] { //
						Permission.MESSAGE_READ, //
						Permission.MESSAGE_WRITE, //
						Permission.MESSAGE_ATTACH_FILES, //
						Permission.MESSAGE_EXT_EMOJI, //
						Permission.MESSAGE_EMBED_LINKS, //
						Permission.MESSAGE_HISTORY, //
						Permission.MESSAGE_ADD_REACTION,//
				})//
					//
				.addCommand("blueprint", (NoArgHandler) event -> handleBlueprintCommand(event))//
				.withHelp("Renders an image of the blueprint string provided. Longer blueprints "
						+ "can be attached as files or linked with pastebin, hastebin, gitlab, or gist URLs.")//
				.withAliases("bp")//
				.addCommand("blueprintJSON", (NoArgHandler) event -> handleBlueprintJsonCommand(event))//
				.withHelp("Provides a dump of the json data in the specified blueprint string.")//
				.addCommand("blueprintUpgradeBelts", (NoArgHandler) event -> handleBlueprintUpgradeBeltsCommand(event))//
				.withHelp("Converts all yellow belts into red belts, and all red belts into blue belts.")//
				.addCommand("blueprintItems", (NoArgHandler) event -> handleBlueprintItemsCommand(event))//
				.withHelp("Prints out all of the items needed by the blueprint.")//
				.withAliases("bpItems")//
				.addCommand("blueprintRawItems", (NoArgHandler) event -> handleBlueprintItemsRawCommand(event))//
				.withHelp("Prints out all of the raw items needed by the blueprint.")//
				.withAliases("bpRawItems")//
				.addCommand("blueprintCounts", (NoArgHandler) event -> handleBlueprintTotalsCommand(event))
				.withHelp("Prints out the total counts of entities, items and tiles needed by the blueprint.")//
				.withAliases("bpCounts")//
				//
				.addCommand("blueprintBookExtract", (NoArgHandler) event -> handleBlueprintBookExtractCommand(event))//
				.withHelp("Provides an collection of blueprint strings contained within the specified blueprint book.")//
				.addCommand("blueprintBookAssemble", (NoArgHandler) event -> handleBlueprintBookAssembleCommand(event))//
				.withHelp(
						"Combines all blueprints (including from other books) from multiple strings into a single book.")//
				//
				.addCommand("prototypeEntity", createPrototypeCommandHandler("entity", table.getEntities()))//
				.withHelp("Provides a dump of the lua data for the specified entity prototype.")//
				.addCommand("prototypeRecipe", createPrototypeCommandHandler("recipe", table.getRecipes()))//
				.withHelp("Provides a dump of the lua data for the specified recipe prototype.")//
				.addCommand("prototypeFluid", createPrototypeCommandHandler("fluid", table.getFluids()))//
				.withHelp("Provides a dump of the lua data for the specified fluid prototype.")//
				.addCommand("prototypeItem", createPrototypeCommandHandler("item", table.getItems()))//
				.withHelp("Provides a dump of the lua data for the specified item prototype.")//
				.addCommand("prototypeTechnology", createPrototypeCommandHandler("technology", table.getTechnologies()))//
				.withHelp("Provides a dump of the lua data for the specified technology prototype.")//
				.addCommand("prototypeEquipment", createPrototypeCommandHandler("equipment", table.getEquipments()))//
				.withHelp("Provides a dump of the lua data for the specified equipment prototype.")//
				.addCommand("prototypeTile", createPrototypeCommandHandler("tile", table.getTiles()))//
				.withHelp("Provides a dump of the lua data for the specified tile prototype.")//
				//
				.addCommand("dataRaw", createDataRawCommandHandler(table::getRaw))//
				.withHelp("Provides a dump of lua from `data.raw` for the specified key.")//
				//
				.addCommand("redditCheckThings",
						(CommandHandler) (event, args) -> handleRedditCheckThingsCommand(event, args))
				//
				.create();

		bot.startAsync().awaitRunning();

		reportingUserID = configJson.getString("reporting_user_id");
		reportingChannelID = configJson.getString("reporting_channel_id");
		hostingChannelID = configJson.getString("hosting_channel_id");

		ServiceFinder.addService(this);
		ServiceFinder.addService(WatchdogReporter.class, new WatchdogReporter() {
			@Override
			public void notifyInactive(String label) {
				TaskReporting reporting = new TaskReporting();
				reporting.addWarning(label + " has gone inactive!");
				sendReport("Watchdog", null, reporting);
			}

			@Override
			public void notifyReactive(String label) {
				TaskReporting reporting = new TaskReporting();
				reporting.addInfo(label + " is now active again!");
				sendReport("Watchdog", null, reporting);
			}
		});
	}

	public URL useDiscordForFileHosting(String fileName, byte[] fileData) throws IOException {
		TextChannel channel = bot.getJDA().getTextChannelById(hostingChannelID);
		Message message = channel.sendFile(fileData, fileName, null).complete();
		return new URL(message.getAttachments().get(0).getUrl());
	}

	public URL useDiscordForImageHosting(String fileName, BufferedImage image, boolean downscaleIfNeeded)
			throws IOException {
		TextChannel channel = bot.getJDA().getTextChannelById(hostingChannelID);
		Message message = channel
				.sendFile(downscaleIfNeeded ? generateDiscordFriendlyPNGImage(image) : WebUtils.getImageData(image),
						fileName, null)
				.complete();
		return new URL(message.getAttachments().get(0).getUrl());
	}
}
