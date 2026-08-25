package com.example.runeconnect;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("runeconnect")
public interface RuneConnectConfig extends Config
{
    @ConfigItem(
            keyName = "linkToken",
            name = "Discord Link Token",
            description = "The link token given to you by the discord bot or a clan leader."
    )
    default String linkToken()
    {
        return "";
    }
}