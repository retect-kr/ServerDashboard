package com.serverdashboard.models;

public class ChatMessage {
    private final long   timestamp;
    private final String channelId;  // null = PM
    private final String sender;
    private final String recipient;  // null = channel msg, playerName = PM
    private final String content;

    public ChatMessage(String channelId, String sender, String recipient, String content) {
        this.timestamp = System.currentTimeMillis();
        this.channelId  = channelId;
        this.sender     = sender;
        this.recipient  = recipient;
        this.content    = content;
    }

    public long   getTimestamp() { return timestamp; }
    public String getChannelId() { return channelId; }
    public String getSender()    { return sender; }
    public String getRecipient() { return recipient; }
    public String getContent()   { return content; }
}
