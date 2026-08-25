package com.example.runeconnect;

import javax.inject.Inject;

import com.google.gson.Gson;
import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.clan.ClanChannel;
import net.runelite.api.clan.ClanChannelMember;
import net.runelite.api.clan.ClanSettings;
import net.runelite.api.clan.ClanTitle;
import net.runelite.api.events.ChatMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.game.ChatIconManager;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

@Slf4j
@PluginDescriptor(
        name = "RuneConnect",
        description = "Sends clan chat, coffer transactions, and rank icons to your clan's RuneBot Discord integration.",
        tags = {"discord", "chat", "clan", "coffer"}
)
public class RuneConnectPlugin extends Plugin
{
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final long REQUEST_TIMEOUT_SECONDS = 10;

    @Inject
    private Gson gson;

    @Inject
    private OkHttpClient okHttpClient;

    private static final class ChatBroadcastItem
    {
        String id;
        String message;
        String timestamp;
        String chatType;
        String sender;
        String rankTitle;
        String rankIconBase64;
    }

    private static final class CofferTransactionPayload
    {
        String id;
        String type;
        long amount;
        String memberName;
    }

    // Raw game text.
    private static final Pattern COFFER_TRANSACTION_PATTERN = Pattern.compile(
            "^(?<memberName>.+?) has (?<action>deposited|withdrawn) (?<amount>one|[\\d,]+) coins? (?<direction>into|from) the coffer\\.$",
            Pattern.CASE_INSENSITIVE
    );

    // Toss out Ironman/HC/UIM status
    private static final Pattern IMG_TAG_PATTERN = Pattern.compile("<img=\\d+>");

    private String stripImageTags(String text)
    {
        return IMG_TAG_PATTERN.matcher(text).replaceAll("").trim();
    }

    @Inject
    private RuneConnectConfig config;

    @Inject
    private DrawManager drawManager;

    @Inject
    private Client client;

    @Inject
    private ChatIconManager chatIconManager;

    @Provides
    RuneConnectConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(RuneConnectConfig.class);
    }

    @Override
    protected void startUp()
    {
        log.info("RuneConnect started");
    }

    @Override
    protected void shutDown()
    {
        log.info("RuneConnect stopped");
    }

    @Subscribe
    public void onChatMessage(ChatMessage event)
    {
        // CLAN_CHAT is what members actually type. CLAN_MESSAGE is from clan system notifications
        if (event.getType() == ChatMessageType.CLAN_CHAT)
        {
            handleClanChatMessage(event);
            return;
        }

        if (event.getType() == ChatMessageType.CLAN_MESSAGE)
        {
            handleClanSystemMessage(event);
        }
    }

    private static final class RankInfo
    {
        final String title;

        // null if capture failed
        final byte[] iconPng;

        RankInfo(String title, byte[] iconPng)
        {
            this.title = title;
            this.iconPng = iconPng;
        }
    }

    private RankInfo resolveRankInfo(String playerName)
    {
        ClanChannel clanChannel = client.getClanChannel();

        if (clanChannel == null)
        {
            return null;
        }

        ClanChannelMember member = clanChannel.findMember(playerName);

        if (member == null)
        {
            return null;
        }

        ClanSettings clanSettings = client.getClanSettings();

        if (clanSettings == null)
        {
            return null;
        }

        ClanTitle title = clanSettings.titleForRank(member.getRank());

        if (title == null)
        {
            return null;
        }

        byte[] iconPng = null;

        try
        {
            BufferedImage icon = chatIconManager.getRankImage(title);

            if (icon != null)
            {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(icon, "png", baos);
                iconPng = baos.toByteArray();
            }
        }
        catch (IOException e)
        {
            log.warn("Failed to capture rank icon for {}", title.getName(), e);
        }

        return new RankInfo(title.getName(), iconPng);
    }

    private void handleClanChatMessage(ChatMessage event)
    {
        // Cleaned once, used for both the rank lookup and the outgoing payload.
        // ClanChannel.findMember() needs the plain name to match the roster.
        String senderName = stripImageTags(event.getName());

        RankInfo rankInfo = resolveRankInfo(senderName);

        ChatBroadcastItem item = new ChatBroadcastItem();
        item.id = UUID.randomUUID().toString();
        item.message = event.getMessage();
        item.timestamp = Instant.now().toString();
        item.chatType = "CLAN_CHAT";
        item.sender = senderName;
        item.rankTitle = (rankInfo != null) ? rankInfo.title : null;
        item.rankIconBase64 = (rankInfo != null && rankInfo.iconPng != null)
                ? Base64.getEncoder().encodeToString(rankInfo.iconPng)
                : null;

        // The endpoint expects a JSON array. This plugin only ever sends one message
        // per call, but the batch shape is what the server parses.
        String json = gson.toJson(new ChatBroadcastItem[] { item });

        postToBot("/runelite/chat", json, "Chat broadcast");
    }

    private void handleClanSystemMessage(ChatMessage event)
    {
        Matcher matcher = COFFER_TRANSACTION_PATTERN.matcher(event.getMessage());

        if (!matcher.matches())
        {
            // Other things come through as clan system messages too (joins, leaves, etc.).
            return;
        }

        String action = matcher.group("action").toLowerCase();
        String type = action.equals("deposited") ? "deposit" : "withdrawal";
        String memberName = stripImageTags(matcher.group("memberName").trim());

        String rawAmount = matcher.group("amount");
        long amount = rawAmount.equalsIgnoreCase("one")
                ? 1
                : Long.parseLong(rawAmount.replace(",", ""));

        drawManager.requestNextFrameListener(image ->
        {
            byte[] imageBytes = null;

            try
            {
                BufferedImage screenshot = ImageUtil.bufferedImageFromImage(image);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(screenshot, "png", baos);
                imageBytes = baos.toByteArray();
            }
            catch (IOException e)
            {
                // Best-effort. Still send the transaction without an image rather than dropping it.
                log.warn("Failed to capture coffer transaction screenshot, sending without one", e);
            }

            sendCofferTransaction(type, amount, memberName, imageBytes);
        });
    }

    private void sendCofferTransaction(String type, long amount, String memberName, byte[] imageBytes)
    {
        String linkToken = config.linkToken();

        if (linkToken.isEmpty())
        {
            return;
        }

        CofferTransactionPayload payload = new CofferTransactionPayload();
        payload.id = UUID.randomUUID().toString();
        payload.type = type;
        payload.amount = amount;
        payload.memberName = memberName;

        String json = gson.toJson(payload);

        String url = SERVER_URL + "/runelite/coffer";
        Request request = imageBytes != null
                ? buildMultipartRequest(url, linkToken, json, imageBytes)
                : buildJsonRequest(url, linkToken, json);

        sendAsync(request, "Coffer transaction");
    }

    private Request buildJsonRequest(String url, String linkToken, String json)
    {
        RequestBody body = RequestBody.create(JSON, json);

        return new Request.Builder()
                .url(url)
                .header("x-runebot-runelite-token", linkToken)
                .post(body)
                .build();
    }

    private Request buildMultipartRequest(String url, String linkToken, String json, byte[] imageBytes)
    {
        MultipartBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("payload_json", null, RequestBody.create(JSON, json))
                .addFormDataPart("file", "screenshot.png",
                        RequestBody.create(MediaType.parse("image/png"), imageBytes))
                .build();

        return new Request.Builder()
                .url(url)
                .header("x-runebot-runelite-token", linkToken)
                .post(body)
                .build();
    }

    private static final String SERVER_URL = "https://runebot-server.com:4467";

    private void postToBot(String path, String json, String label)
    {
        String linkToken = config.linkToken();

        if (linkToken.isEmpty())
        {
            return;
        }

        RequestBody body = RequestBody.create(JSON, json);

        Request request = new Request.Builder()
                .url(SERVER_URL + path)
                .header("x-runebot-runelite-token", linkToken)
                .post(body)
                .build();

        sendAsync(request, label);
    }

    private void sendAsync(Request request, String label)
    {
        okHttpClient.newBuilder()
                .callTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
                .newCall(request)
                .enqueue(new Callback()
                {
                    @Override
                    public void onResponse(Call call, Response response)
                    {
                        log.debug("{} responded: {}", label, response.code());
                        response.close();
                    }

                    @Override
                    public void onFailure(Call call, IOException e)
                    {
                        log.debug("Failed to reach {} endpoint", label.toLowerCase(), e);
                    }
                });
    }
}