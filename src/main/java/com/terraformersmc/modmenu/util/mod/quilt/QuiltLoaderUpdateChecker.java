package com.terraformersmc.modmenu.util.mod.quilt;

import java.io.IOException;
import java.net.URI;
import java.util.Optional;

import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.RequestBuilder;
import org.apache.http.util.EntityUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import org.quiltmc.loader.api.QuiltLoader;
import org.quiltmc.loader.api.Version;
import org.quiltmc.loader.api.VersionFormatException;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.terraformersmc.modmenu.api.UpdateChannel;
import com.terraformersmc.modmenu.api.UpdateChecker;
import com.terraformersmc.modmenu.api.UpdateInfo;
import com.terraformersmc.modmenu.util.HttpUtil;
import com.terraformersmc.modmenu.util.JsonUtil;

import net.minecraft.text.Text;
import net.minecraft.text.TranslatableText;

public class QuiltLoaderUpdateChecker implements UpdateChecker {
	public static final Logger LOGGER = LogManager.getLogger("Mod Menu/Quilt Update Checker");
	private static final URI LOADER_VERSIONS = URI.create("https://meta.quiltmc.org/v3/versions/loader");

	@Override
	public UpdateInfo checkForUpdates() {
		UpdateInfo result = null;

		try {
			result = checkForUpdates0();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (IOException e) {
			LOGGER.error("Failed Quilt Loader update check!", e);
		}

		return result;
	}

	private static UpdateInfo checkForUpdates0() throws IOException, InterruptedException {
		UpdateChannel preferredChannel = UpdateChannel.getUserPreference();

		RequestBuilder request = RequestBuilder.get().setUri(LOADER_VERSIONS);
		HttpResponse response = HttpUtil.request(request);

		int status = response.getStatusLine().getStatusCode();

		if (status != 200) {
			LOGGER.warn("Quilt Meta responded with a non-200 status: {}!", status);
			return null;
		}

		Header[] contentType = response.getHeaders("Content-Type");

		if (contentType.length == 0 || !contentType[0].getValue().contains("application/json")) {
			LOGGER.warn("Quilt Meta responded with a non-json content type, aborting loader update check!");
			return null;
		}

		JsonElement data = new JsonParser().parse(EntityUtils.toString(response.getEntity()));

		if (!data.isJsonArray()) {
			LOGGER.warn("Received invalid data from Quilt Meta, aborting loader update check!");
			return null;
		}

		Version.Semantic match = null;

		for (JsonElement child : data.getAsJsonArray()) {
			if (!child.isJsonObject()) {
				continue;
			}

			JsonObject object = child.getAsJsonObject();
			Optional<String> version = JsonUtil.getString(object, "version");

			if (!version.isPresent()) {
				continue;
			}

			Version.Semantic parsed;

			try {
				parsed = Version.Semantic.of(version.get());
			} catch (VersionFormatException e) {
				continue;
			}

			if (preferredChannel == UpdateChannel.RELEASE && !parsed.preRelease().equals("")) {
				continue;
			} else if (preferredChannel == UpdateChannel.BETA && !isStableOrBeta(parsed.preRelease())) {
				continue;
			}

			if (match == null || isNewer(parsed, match)) {
				match = parsed;
			}
		}

		Version.Semantic current = getCurrentVersion();

		if (match == null || !isNewer(match, current)) {
			LOGGER.debug("Quilt Loader is up to date.");
			return null;
		}

		LOGGER.debug("Quilt Loader has a matching update available!");
		return new QuiltLoaderUpdateInfo(match);
	}

	private static boolean isNewer(Version.Semantic self, Version.Semantic other) {
		return self.compareTo(other) > 0;
	}

	private static Version.Semantic getCurrentVersion() {
		return QuiltLoader.getModContainer("quilt_loader").get().metadata().version().semantic();
	}

	private static boolean isStableOrBeta(String preRelease) {
		return preRelease.isEmpty() || preRelease.startsWith("beta") || preRelease.startsWith("pre") || preRelease.startsWith("rc");
	}

	private static class QuiltLoaderUpdateInfo implements UpdateInfo {
		private final Version.Semantic version;

		private QuiltLoaderUpdateInfo(Version.Semantic version) {
			this.version = version;
		}

		@Override
		public boolean isUpdateAvailable() {
			return true;
		}

		@Override
		public @Nullable Text getUpdateMessage() {
			return new TranslatableText("modmenu.install_version", this.version.raw());
		}

		@Override
		public String getDownloadLink() {
			return "https://ornithemc.net";
		}

		@Override
		public UpdateChannel getUpdateChannel() {
			String preRelease = this.version.preRelease();

			if (preRelease.isEmpty()) {
				return UpdateChannel.RELEASE;
			} else if (isStableOrBeta(preRelease)) {
				return UpdateChannel.BETA;
			} else {
				return UpdateChannel.ALPHA;
			}
		}
	}
}
